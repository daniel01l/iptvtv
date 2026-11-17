package pl.iptv.tv.remote

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.QueueItem
import pl.iptv.tv.data.StubeQueue
import pl.iptv.tv.data.YtKeyless
import pl.iptv.tv.data.YtVideo
import pl.iptv.tv.ui.STubeActivity
import pl.iptv.tv.ui.YtEngine
import pl.iptv.tv.data.YtChapters
import pl.iptv.tv.ui.YtPlayerActivity
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Sterowanie STube z telefonu (przeglądarka w tej samej sieci Wi-Fi): strona `/stube` i API `/api/stube/…` na serwerze
 * [RemoteServer] (port 8765). Dostęp tylko z 4-cyfrowym kodem parowania z ekranu TV ([Prefs.stubePin], nagłówek `X-Pin`);
 * po [MAX_FAILS] błędnych kodach – blokada na [LOCK_MS] (4 cyfry nie dają się zgadnąć seryjnie).
 *
 * Polecenia (`POST /api/stube/cmd`, JSON `{"cmd": …, "arg": …}`): toggle, play, pause, seek (arg ms), chapter_next, chapter_prev,
 * next, stop – odtwarzacz; play_video / queue_add / queue_next (arg: link lub ID filmu), search (arg: fraza), open (STube).
 */
object StubeRemote {
    const val MAX_FAILS = 5
    const val LOCK_MS = 60_000L
    private var fails = 0
    private var lockedUntil = 0L
    private val main = Handler(Looper.getMainLooper())

    /** Kod parowania (tworzony przy pierwszym użyciu, nowy – [newPin]). */
    fun ensurePin(): String {
        if (!Regex("\\d{4}").matches(Prefs.stubePin)) Prefs.stubePin = (1000..9999).random().toString()
        return Prefs.stubePin
    }
    fun newPin(): String { Prefs.stubePin = ""; fails = 0; lockedUntil = 0; return ensurePin() }

    /** Adres strony sterowania (pierwszy adres IPv4 w sieci lokalnej). */
    fun pageUrl(): String? = RemoteServer.localIps().firstOrNull()?.let { "http://$it:${RemoteServer.PORT}/stube" }

    /** Włączenie sterowania: serwer HTTP działa, dopóki sterowanie jest włączone. */
    fun setEnabled(c: Context, on: Boolean) {
        Prefs.stubeRemote = on
        if (on) { ensurePin(); RemoteServer.start(c) } else if (!Prefs.remoteOn) RemoteServer.stop()
    }

    /** Sprawdzenie kodu z blokadą po serii błędów. Null – poprawny, inaczej komunikat. */
    @Synchronized fun authorize(pin: String?, now: Long = SystemClock.elapsedRealtime()): String? {
        if (!Prefs.stubeRemote) return "Sterowanie z telefonu jest wyłączone (Ustawienia → Konto YouTube → Połącz z telefonem)"
        if (now < lockedUntil) return "Zbyt wiele błędnych kodów – spróbuj za ${(lockedUntil - now) / 1000 + 1} s"
        if (pin != null && pin == ensurePin()) { fails = 0; return null }
        if (++fails >= MAX_FAILS) { fails = 0; lockedUntil = now + LOCK_MS }
        return "Błędny kod parowania"
    }

    /** Odpowiedź HTTP: kod, typ, treść. Null – ścieżka nie należy do STube. */
    fun handle(method: String, path: String, headers: Map<String, String>, body: ByteArray): Triple<Int, String, String>? {
        if (method == "GET" && (path == "/stube" || path.startsWith("/stube?") || path == "/stube/")) return Triple(200, "text/html", PAGE)
        if (!path.startsWith("/api/stube/")) return null
        authorize(headers["x-pin"])?.let { return Triple(403, "application/json", JSONObject().put("ok", false).put("message", it).toString()) }
        val res = when {
            method == "POST" && path.startsWith("/api/stube/pair") -> JSONObject().put("ok", true).put("name", RemoteServer.deviceName)
            method == "GET" && path.startsWith("/api/stube/status") -> status()
            method == "POST" && path.startsWith("/api/stube/cmd") -> {
                val j = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull() ?: return Triple(400, "application/json", err("Błędne polecenie"))
                command(j.optString("cmd"), j.optString("arg"))
            }
            else -> return Triple(404, "application/json", err("Nieznane polecenie"))
        }
        return Triple(if (res.optBoolean("ok", true)) 200 else 400, "application/json", res.toString())
    }

    private fun err(m: String) = JSONObject().put("ok", false).put("message", m).toString()
    private fun ok(m: String) = JSONObject().put("ok", true).put("message", m)
    private fun fail(m: String) = JSONObject().put("ok", false).put("message", m)

    /** ID filmu z linku YouTube (watch, youtu.be, shorts) albo 11-znakowego ID. */
    fun videoId(arg: String): String? {
        val s = arg.trim()
        if (Regex("^[A-Za-z0-9_-]{11}$").matches(s)) return s
        YtKeyless.idOf(s)?.let { return it }
        return Regex("youtube\\.com/(?:shorts|live)/([A-Za-z0-9_-]{11})").find(s)?.groupValues?.get(1)
    }

    /** Wykonanie na wątku głównym z czekaniem na wynik (maks. 3 s). */
    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val t = FutureTask(block)
        main.post(t)
        return runCatching { t.get(3, TimeUnit.SECONDS) }.getOrNull()
    }

    private val app get() = RemoteServer.appContext

    fun command(cmd: String, arg: String): JSONObject = when (cmd) {
        "toggle", "play", "pause", "seek", "chapter_next", "chapter_prev", "next", "stop" -> onMain {
            // odtwarzacz w usłudze – działa tak samo przy otwartym ekranie, w PiP i w tle
            val p = YtEngine.player?.takeIf { YtEngine.active } ?: return@onMain fail("Nic nie jest odtwarzane w STube")
            val chapters = YtEngine.playable?.chapters.orEmpty()
            when (cmd) {
                "toggle" -> p.playWhenReady = !p.playWhenReady
                "play" -> p.playWhenReady = true
                "pause" -> p.playWhenReady = false
                "seek" -> p.seekTo((p.currentPosition + (arg.toLongOrNull() ?: 0)).coerceAtLeast(0))
                "chapter_next" -> p.seekTo(YtChapters.nextStart(chapters, p.currentPosition) ?: return@onMain fail("Brak następnego rozdziału"))
                "chapter_prev" -> p.seekTo(YtChapters.prevStart(chapters, p.currentPosition) ?: return@onMain fail("Ten film nie ma rozdziałów"))
                "next" -> YtEngine.next()
                "stop" -> YtEngine.stop()
            }
            ok("OK")
        } ?: fail("Brak odpowiedzi odtwarzacza")
        "play_video" -> videoId(arg)?.let { id ->
            onMain { app.startActivity(YtPlayerActivity.intent(app, YtVideo(id, "", "")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            ok("Odtwarzanie na TV")
        } ?: fail("To nie jest link do filmu YouTube")
        "queue_add", "queue_next" -> videoId(arg)?.let { id ->
            runBlocking { StubeQueue.add(QueueItem(kind = QueueItem.KIND_YT, ref = id, title = "Film z telefonu ($id)"), next = cmd == "queue_next") }
            ok(if (cmd == "queue_next") "Następny w kolejce" else "Dodano do kolejki")
        } ?: fail("To nie jest link do filmu YouTube")
        "search" -> arg.trim().takeIf { it.isNotEmpty() }?.let { q ->
            onMain { app.startActivity(Intent(app, STubeActivity::class.java).putExtra(STubeActivity.EXTRA_QUERY, q)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            ok("Wyszukiwanie na TV: $q")
        } ?: fail("Wpisz frazę")
        "open" -> { onMain { app.startActivity(Intent(app, STubeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }; ok("STube otwarte") }
        else -> fail("Nieznane polecenie")
    }

    /** Stan dla telefonu: bieżący film (ekran lub tło) i kolejka. */
    fun status(): JSONObject {
        val now = onMain {
            YtEngine.player?.takeIf { YtEngine.active }?.let { p ->
                val ch = YtEngine.playable?.chapters.orEmpty()
                JSONObject().put("id", YtEngine.video?.id).put("title", YtEngine.playable?.title?.ifBlank { null } ?: YtEngine.video?.title)
                    .put("playing", p.isPlaying).put("position", p.currentPosition).put("duration", p.duration.coerceAtLeast(0))
                    .put("chapter", YtChapters.indexAt(ch, p.currentPosition).takeIf { it >= 0 }?.let { ch[it].title } ?: "")
                    .put("background", YtPlayerActivity.active?.get()?.client == null)
            }
        }
        val q = runBlocking { StubeQueue.items() }
        return JSONObject().put("ok", true).put("device", RemoteServer.deviceName).put("now", now ?: JSONObject.NULL)
            .put("queue", JSONArray(q.map { JSONObject().put("title", it.title).put("kind", it.kind).put("sub", it.subtitle) }))
    }

    private val PAGE = """<!doctype html><html lang="pl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>STube – pilot</title><style>
:root{--bg:#0f0f0f;--card:#1c1c1c;--fg:#f1f1f1;--mut:#aaa;--acc:#ff0033}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:16px system-ui,sans-serif;padding:16px;max-width:520px;margin:auto}
h1{font-size:22px;margin:4px 0 12px}.card{background:var(--card);border-radius:14px;padding:14px;margin-bottom:12px}
input{width:100%;padding:12px;border-radius:10px;border:1px solid #333;background:#111;color:var(--fg);font-size:16px}
button{padding:14px 10px;border:0;border-radius:12px;background:#2a2a2a;color:var(--fg);font-size:17px;cursor:pointer}
button.p{background:var(--acc);color:#fff}.g{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}.r{display:flex;gap:8px;margin-top:8px}.r>*{flex:1}
.m{color:var(--mut);font-size:14px}#bar{height:4px;background:#333;border-radius:2px;margin-top:8px}#fill{height:4px;background:var(--acc);width:0;border-radius:2px}
li{margin:4px 0}</style></head><body>
<h1>▶ STube – pilot</h1>
<div class="card" id="pairBox"><div class="m">Wpisz 4-cyfrowy kod z ekranu TV (Ustawienia → Konto YouTube → Połącz z telefonem).</div>
<div class="r"><input id="pin" inputmode="numeric" maxlength="4" placeholder="Kod"><button class="p" onclick="pair()">Połącz</button></div></div>
<div id="ui" style="display:none">
<div class="card"><div id="title">—</div><div class="m" id="sub"></div><div id="bar"><div id="fill"></div></div></div>
<div class="card g">
<button onclick="cmd('seek',-10000)">⏪ 10 s</button><button class="p" onclick="cmd('toggle')">⏯</button><button onclick="cmd('seek',10000)">10 s ⏩</button>
<button onclick="cmd('chapter_prev')">⏮ Rozdział</button><button onclick="cmd('next')">⏭ Następny</button><button onclick="cmd('chapter_next')">Rozdział ⏭</button>
</div>
<div class="card"><input id="link" placeholder="Link YouTube lub ID filmu">
<div class="r"><button class="p" onclick="cmd('play_video',v('link'))">▶ Odtwórz</button><button onclick="cmd('queue_next',v('link'))">⏭ Jako następny</button><button onclick="cmd('queue_add',v('link'))">＋ Kolejka</button></div></div>
<div class="card"><input id="q" placeholder="Szukaj w STube…" onkeydown="if(event.key=='Enter')cmd('search',v('q'))">
<div class="r"><button onclick="cmd('search',v('q'))">🔎 Szukaj na TV</button><button onclick="cmd('stop')">⏹ Zamknij film</button></div></div>
<div class="card"><b>Kolejka</b><ol id="queue" class="m"></ol></div>
</div><div class="m" id="msg"></div>
<script>
let pin=localStorage.getItem('stubePin')||'';const v=id=>document.getElementById(id).value;
function say(t){document.getElementById('msg').textContent=t}
async function api(path,opt={}){opt.headers=Object.assign({'X-Pin':pin},opt.headers||{});const r=await fetch(path,opt);return r.json()}
async function pair(){pin=v('pin');const j=await api('/api/stube/pair',{method:'POST'});if(j.ok){localStorage.setItem('stubePin',pin);show()}else say(j.message)}
async function cmd(c,a){const j=await api('/api/stube/cmd',{method:'POST',body:JSON.stringify({cmd:c,arg:String(a??'')})});say(j.message||'');refresh()}
function fmt(ms){ms=Math.max(0,ms|0);let s=Math.floor(ms/1000),h=Math.floor(s/3600),m=Math.floor(s%3600/60);s%=60;return(h?h+':'+String(m).padStart(2,'0'):m)+':'+String(s).padStart(2,'0')}
async function refresh(){try{const j=await api('/api/stube/status');if(!j.ok){document.getElementById('ui').style.display='none';document.getElementById('pairBox').style.display='';say(j.message);return}
const n=j.now;document.getElementById('title').textContent=n?(n.title||n.id):'Nic nie jest odtwarzane';
document.getElementById('sub').textContent=n?((n.playing?'▶ ':'⏸ ')+fmt(n.position)+' / '+fmt(n.duration)+(n.chapter?' • '+n.chapter:'')+(n.background?' • w tle':'')):j.device;
document.getElementById('fill').style.width=n&&n.duration?(100*n.position/n.duration)+'%':'0';
document.getElementById('queue').innerHTML=j.queue.map(q=>'<li>'+(q.kind=='iptv'?'📺 ':'▶ ')+q.title.replace(/</g,'&lt;')+'</li>').join('')||'<li>pusta</li>'}catch(e){}}
function show(){document.getElementById('pairBox').style.display='none';document.getElementById('ui').style.display='';refresh()}
if(pin)show();setInterval(()=>{if(pin)refresh()},2000);
</script></body></html>"""
}
