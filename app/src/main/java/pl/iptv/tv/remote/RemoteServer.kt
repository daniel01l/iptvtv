package pl.iptv.tv.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import pl.iptv.tv.data.M3uParser
import pl.iptv.tv.data.Playlists
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.StubeImport
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Mały serwer HTTP w sieci lokalnej: przyjmuje listę z przeglądarki (formularz) lub z aplikacji na telefonie.
 * Wymaga kodu PIN wyświetlanego na tym urządzeniu.
 */
object RemoteServer {
    const val PORT = 8765
    const val SERVICE_TYPE = "_iptvtv._tcp."
    private const val MAX_BODY = 60 * 1024 * 1024

    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(3)
    private var nsd: NsdManager.RegistrationListener? = null
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    /** Wywoływane na wątku głównym po odebraniu listy. */
    var listener: (() -> Unit)? = null
    /** Odświeżenie ekranu po zmianach z zewnątrz (polecenia administratora). */
    fun notifyChanged() { main.post { listener?.invoke() } }

    val running get() = server?.isClosed == false
    val deviceName: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    fun ensurePin(): String {
        if (Prefs.remotePin.length != 4) Prefs.remotePin = (1000..9999).random().toString()
        return Prefs.remotePin
    }

    fun localIps(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().map { it.hostAddress!! }
    }.getOrDefault(emptyList())

    val appContext: Context get() = app

    /** Kontekst bez uruchamiania serwera (dla przekaźnika internetowego). */
    fun init(c: Context) { app = c.applicationContext }

    @Synchronized fun start(c: Context) {
        app = c.applicationContext
        if (running) return
        ensurePin()
        val s = runCatching { ServerSocket(PORT) }.getOrNull() ?: return
        server = s
        Thread({
            while (!s.isClosed) {
                val sock = runCatching { s.accept() }.getOrNull() ?: break
                pool.execute { runCatching { handle(sock) }; runCatching { sock.close() } }
            }
        }, "RemoteServer").start()
        register()
    }

    @Synchronized fun stop() {
        runCatching { server?.close() }; server = null
        nsd?.let { l -> runCatching { (app.getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(l) } }; nsd = null
    }

    /** Ponowna rejestracja usługi NSD (zmiana atrybutów, np. włączenie odbioru linków). */
    @Synchronized fun refreshService() {
        if (!running) return
        nsd?.let { l -> runCatching { (app.getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(l) } }; nsd = null
        register()
    }

    private fun register() {
        // atrybuty TXT: telefon widzi z listy usług nazwę, wersję protokołu i to, że odbiór linków jest włączony (PIN nie jest publikowany)
        val info = NsdServiceInfo().apply {
            serviceName = "IptvTv-$deviceName"; serviceType = SERVICE_TYPE; port = PORT
            runCatching {
                setAttribute("v", "2"); setAttribute("name", deviceName.take(60))
                setAttribute("cast", if (Prefs.castReceiver) "1" else "0"); setAttribute("api", "/api/cast"); setAttribute("pin", "1")
            }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        runCatching { (app.getSystemService(Context.NSD_SERVICE) as NsdManager).registerService(info, NsdManager.PROTOCOL_DNS_SD, l); nsd = l }
    }

    // ---------------- HTTP ----------------

    private class Req(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private fun read(sock: Socket): Req? {
        sock.soTimeout = 30_000
        val inp = BufferedInputStream(sock.getInputStream())
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = inp.read(); if (b < 0) return null
            head.write(b); last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() > 16_384) return null
        }
        val lines = head.toString("ISO-8859-1").split("\r\n")
        val (method, path) = lines[0].split(" ").let { it[0] to it.getOrElse(1) { "/" } }
        val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len > MAX_BODY) return null
        val body = ByteArray(len); var off = 0
        while (off < len) { val r = inp.read(body, off, len - off); if (r < 0) break; off += r }
        return Req(method, path, headers, body)
    }

    private fun respond(sock: Socket, code: Int, type: String, body: String) {
        val b = body.toByteArray(Charsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Bad Request" }
        sock.getOutputStream().apply {
            write("HTTP/1.1 $code $status\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(b); flush()
        }
    }

    private fun handle(sock: Socket) {
        val r = read(sock) ?: return respond(sock, 400, "text/plain", "Błędne żądanie")
        // sterowanie STube z telefonu (strona /stube, API /api/stube/… z kodem parowania)
        StubeRemote.handle(r.method, r.path, r.headers, r.body)?.let { (code, type, body) -> return respond(sock, code, type, body) }
        when {
            r.method == "GET" && r.path.startsWith("/api/info") ->
                respond(sock, 200, "application/json", JSONObject().put("name", deviceName).put("app", "IptvTv").put("cast", Prefs.castReceiver).toString())
            // (7.15) „wyślij na telewizor”: link (playlista YouTube / Spotify, film YouTube) → kolejka i odtwarzanie ([CastReceiver])
            r.method == "POST" && r.path.startsWith("/api/cast") -> {
                val res = receiveCast(r.headers["x-pin"].orEmpty(), dec(r.headers["x-url"]).ifBlank { String(r.body, Charsets.UTF_8) })
                respond(sock, if (res.first) 200 else 403, "application/json", JSONObject().put("ok", res.first).put("message", res.second).toString())
            }
            // (7.21) klucz TMDB z telefonu (kod QR w Stremio → „Klucz TMDB”): strona /tmdb i zapis /api/tmdb z kodem PIN
            r.method == "GET" && r.path.startsWith("/tmdb") -> respond(sock, 200, "text/html", tmdbPage())
            r.method == "POST" && r.path.startsWith("/api/tmdb") -> {
                val ok = r.headers["x-pin"].orEmpty() == Prefs.remotePin
                val key = String(r.body, Charsets.UTF_8).trim()
                if (ok && key.length in 16..64) { Prefs.tmdbApiKey = key; pl.iptv.tv.data.StremioTmdb.clearCache(); pl.iptv.tv.ui.TmdbKeyUi.onRemoteKey?.invoke() }
                respond(sock, if (ok) 200 else 403, "application/json", JSONObject().put("ok", ok && key.length in 16..64).put("message", if (!ok) "Błędny kod PIN" else if (key.length !in 16..64) "Nieprawidłowy klucz" else "Zapisano – wróć do telewizora").toString())
            }
            r.method == "GET" -> respond(sock, 200, "text/html", page())
            r.method == "POST" && r.path.startsWith("/api/playlist") -> {
                val h = r.headers
                val res = receive(h["x-pin"].orEmpty(), dec(h["x-name"]), dec(h["x-url"]), r.body, dec(h["x-epg"]), dec(h["x-expiry"]), h["x-activate"] == "1")
                respond(sock, if (res.first) 200 else 403, "application/json", JSONObject().put("ok", res.first).put("message", res.second).toString())
            }
            r.method == "POST" && r.path.startsWith("/api/apk") -> {
                val res = receiveApk(r.headers["x-pin"].orEmpty(), r.body)
                respond(sock, if (res.first) 200 else 403, "application/json", JSONObject().put("ok", res.first).put("message", res.second).toString())
            }
            r.method == "POST" && r.path.startsWith("/upload-apk") -> {
                val f = multipart(r)
                val res = receiveApk(f["pin"]?.text.orEmpty(), f["apk"]?.bytes ?: ByteArray(0))
                respond(sock, 200, "text/html", page(if (res.first) "✔ ${res.second}" else "✖ ${res.second}"))
            }
            r.method == "POST" && r.path.startsWith("/upload") -> {
                val f = multipart(r)
                val res = receive(f["pin"]?.text.orEmpty(), f["name"]?.text.orEmpty(), f["url"]?.text.orEmpty(), f["file"]?.bytes ?: ByteArray(0),
                    f["epg"]?.text.orEmpty(), f["expiry"]?.text.orEmpty(), f["activate"] != null)
                respond(sock, 200, "text/html", page(if (res.first) "✔ ${res.second}" else "✖ ${res.second}"))
            }
            else -> respond(sock, 404, "text/plain", "Nie znaleziono")
        }
    }

    private fun dec(s: String?) = s?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }.orEmpty()

    private class Part(val bytes: ByteArray) { val text get() = String(bytes, Charsets.UTF_8).trim() }

    private fun multipart(r: Req): Map<String, Part> {
        val ct = r.headers["content-type"].orEmpty()
        val boundary = ct.substringAfter("boundary=", "").trim('"')
        if (boundary.isEmpty()) return emptyMap()
        val s = String(r.body, Charsets.ISO_8859_1)
        val out = HashMap<String, Part>()
        for (chunk in s.split("--$boundary")) {
            val sep = chunk.indexOf("\r\n\r\n"); if (sep < 0) continue
            val hdr = chunk.substring(0, sep)
            val name = Regex("name=\"([^\"]*)\"").find(hdr)?.groupValues?.get(1) ?: continue
            val data = chunk.substring(sep + 4).removeSuffix("\r\n")
            out[name] = Part(data.toByteArray(Charsets.ISO_8859_1))
        }
        return out
    }

    /** Odebrany plik aktualizacji: zapis + weryfikacja + instalacja. */
    fun receiveApk(pin: String, bytes: ByteArray): Pair<Boolean, String> {
        if (pl.iptv.tv.BuildConfig.SECURE) return false to "Ta wersja instaluje aktualizacje tylko z oficjalnych wydań"
        if (pin != Prefs.remotePin) return false to "Błędny kod PIN"
        if (bytes.size < 1000) return false to "Brak pliku aktualizacji"
        val f = File(app.cacheDir, "update.apk"); f.writeBytes(bytes)
        return pl.iptv.tv.update.Updater.handleIncoming(app, f)
    }

    /**
     * (7.14) Odebrany tekst / plik, który nie jest listą kanałów M3U, ale linkiem do playlisty muzycznej (YouTube, Spotify) albo listą
     * tytułów: przekazany do STube ([StubeImport.offer]) – okno „＋” pokazuje podgląd. Null – to zwykła lista M3U (obsługa bez zmian).
     */
    private fun musicPlaylistOffer(url: String, bytes: ByteArray): Pair<Boolean, String>? {
        val text = if (bytes.isNotEmpty()) String(bytes, Charsets.UTF_8).takeIf { !it.trimStart().startsWith("#EXTM3U") && M3uParser.parse(it).channels.isEmpty() } ?: return null else url.trim()
        if (StubeImport.classify(text) == null && !(bytes.isNotEmpty() && StubeImport.looksLikeTrackList(text))) return null
        StubeImport.offer(text)
        main.post { pl.iptv.tv.ui.I18n.makeToast(app, "🎵 Otrzymano playlistę dla STube – otwórz STube i wybierz ＋", Toast.LENGTH_LONG).show() }
        return true to "Playlista przekazana do STube na urządzeniu $deviceName"
    }

    /** (7.15) Link z telefonu do odtworzenia (PIN jak przy listach); (przyjęto, komunikat). */
    fun receiveCast(pin: String, text: String): Pair<Boolean, String> {
        if (!Prefs.castReceiver) return false to "Odbiór linków do odtwarzania jest wyłączony na tym urządzeniu"
        if (pin != Prefs.remotePin) return false to "Błędny kod PIN"
        return CastReceiver.handle(app, text)
    }

    /** Serwer ma działać: odbiór list, sterowanie STube albo odbiór linków do odtwarzania. */
    val wanted: Boolean get() = Prefs.remoteOn || Prefs.stubeRemote || Prefs.castReceiver

    /** Zapisuje odebraną listę; zwraca (sukces, komunikat). */
    fun receive(pin: String, name: String, url: String, bytes: ByteArray, epg: String, expiry: String, activate: Boolean): Pair<Boolean, String> {
        if (pin != Prefs.remotePin) return false to "Błędny kod PIN"
        // (7.14) link do playlisty muzycznej (YouTube / Spotify) albo lista „Wykonawca – Tytuł” → STube (ten sam kanał i PIN co lista M3U)
        musicPlaylistOffer(url, bytes)?.let { return it }
        val exp = Playlists.parseDate(expiry.replace('-', '.').let { e ->
            // pole <input type=date> wysyła RRRR-MM-DD
            if (Regex("\\d{4}\\.\\d{2}\\.\\d{2}").matches(e)) e.split('.').reversed().joinToString(".") else e
        }) ?: return false to "Błędna data ważności (DD.MM.RRRR)"
        val source: String
        val count: Int
        if (bytes.isNotEmpty()) {
            count = M3uParser.parse(String(bytes, Charsets.UTF_8)).channels.size
            if (count == 0) return false to "Plik nie zawiera kanałów M3U"
            val dir = File(app.filesDir, "received").apply { mkdirs() }
            val f = File(dir, "lista_${System.currentTimeMillis()}.m3u"); f.writeBytes(bytes)
            source = f.path
        } else if (url.startsWith("http")) { source = url.trim(); count = -1 }
        else return false to "Podaj plik lub link do listy"
        // bez podanego EPG lista dostaje dotychczasowy adres programu TV (nie trzeba go wpisywać ponownie)
        val p = Playlists.add(name.ifBlank { "Lista z telefonu" }, source, epg.trim().ifBlank { Prefs.effectiveEpgUrl }, exp)
        main.post {
            pl.iptv.tv.ui.I18n.makeToast(app, "📥 Otrzymano listę: ${p.name}" + (if (count > 0) " ($count kanałów)" else ""), Toast.LENGTH_LONG).show()
            if (activate || Prefs.remoteAutoActivate) CoroutineScope(Dispatchers.Main).launch {
                runCatching { Repository.switchPlaylist(p.id) }; listener?.invoke()
            } else listener?.invoke()
        }
        return true to "Lista „${p.name}” zapisana na urządzeniu $deviceName" + (if (count > 0) " ($count kanałów)" else "")
    }

    private fun page(msg: String = "") = """<!doctype html><html lang="pl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>IptvTv – wyślij listę</title>
<style>body{font-family:sans-serif;background:#0f1218;color:#eee;max-width:560px;margin:auto;padding:16px}
input,button{width:100%;padding:12px;margin:6px 0 14px;border-radius:8px;border:1px solid #445;background:#171c25;color:#eee;font-size:16px;box-sizing:border-box}
button{background:#2f80ed;border:0;font-weight:bold}.m{padding:12px;border-radius:8px;background:#26324a}label{font-size:14px;color:#9aa3b2}</style></head>
<body><h2>📥 Wyślij listę na: ${deviceName.replace("<", "")}</h2>${if (msg.isNotEmpty()) "<p class=m>$msg</p>" else ""}
<form method="post" action="/upload" enctype="multipart/form-data">
<label>Kod PIN (widoczny na telewizorze)</label><input name="pin" inputmode="numeric" maxlength="4" required>
<label>Nazwa listy</label><input name="name" placeholder="np. Moja lista">
<label>Plik .m3u</label><input type="file" name="file" accept=".m3u,.m3u8,audio/x-mpegurl,text/plain">
<label>…albo link do listy (M3U) – lub do playlisty YouTube / Spotify (trafi do STube)</label><input name="url" placeholder="http://…/lista.m3u  lub  https://open.spotify.com/playlist/…">
<label>Link do EPG (opcjonalnie)</label><input name="epg" placeholder="http://…/epg.xml.gz">
<label>Data ważności (opcjonalnie)</label><input type="date" name="expiry">
<label><input type="checkbox" name="activate" style="width:auto"> Od razu ustaw jako aktywną</label>
<button type="submit">Wyślij</button></form>
<h3>⬆ Aktualizacja aplikacji</h3><form method="post" action="/upload-apk" enctype="multipart/form-data">
<label>Kod PIN</label><input name="pin" inputmode="numeric" maxlength="4" required>
<label>Plik IptvTv.apk</label><input type="file" name="apk" accept=".apk" required>
<button type="submit">Zaktualizuj</button></form></body></html>"""
}

/** (7.21) Prosta strona do wpisania klucza TMDB z telefonu (kod QR na telewizorze). */
private fun tmdbPage(): String = """<!doctype html><html lang="pl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Klucz TMDB</title><style>body{font-family:sans-serif;background:#0F141F;color:#F8FAFC;margin:0;padding:24px}input,button{width:100%;box-sizing:border-box;font-size:18px;padding:12px;margin:8px 0;border-radius:12px;border:1px solid #334155;background:#121722;color:#F8FAFC}button{background:#3B82F6;border:0;font-weight:bold}small{color:#94A3B8}</style></head>
<body><h2>Klucz TMDB</h2><p><small>Klucz API (v3) z themoviedb.org → Ustawienia → API. Włącza Top 10, polskie opisy i części filmów.</small></p>
<input id="k" placeholder="Klucz API (32 znaki)" autocomplete="off"><input id="p" placeholder="PIN z telewizora" inputmode="numeric">
<button onclick="fetch('/api/tmdb',{method:'POST',headers:{'x-pin':document.getElementById('p').value},body:document.getElementById('k').value}).then(r=>r.json()).then(j=>document.getElementById('m').textContent=j.message)">Zapisz</button>
<p id="m"></p><p><small>This product uses the TMDB API but is not endorsed or certified by TMDB.</small></p></body></html>"""
