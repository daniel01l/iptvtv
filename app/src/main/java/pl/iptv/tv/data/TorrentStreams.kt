package pl.iptv.tv.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Źródła P2P (torrent) Stremio we wbudowanym odtwarzaczu – przez serwer strumieniujący, który pobiera torrent i podaje go
 * jako zwykły strumień HTTP (odtwarzacz nie zawiera własnego silnika torrent – bez obciążania aplikacji i bez bibliotek
 * natywnych). Obsługiwane:
 * - **TorrServer** (aplikacja na Android TV albo komputer; domyślnie port 8090): `/stream/…?link=<infoHash>&index=<n+1>&play`,
 * - **serwer Stremio** (Stremio Service na komputerze w sieci lokalnej albo aplikacja Stremio; port 11470): `/<infoHash>/<n>`.
 *
 * Adres serwera: [Prefs.torrentServer] (pusty – samo wyszukanie na tym urządzeniu: TorrServer, potem serwer Stremio).
 */
object TorrentStreams {
    enum class Kind { TORRSERVER, STREMIO }
    data class Server(val base: String, val kind: Kind) {
        val label get() = (if (kind == Kind.TORRSERVER) "TorrServer" else "serwer Stremio") + " (" + base.removePrefix("http://") + ")"
    }

    const val TORRSERVER_LOCAL = "http://127.0.0.1:8090"
    const val STREMIO_LOCAL = "http://127.0.0.1:11470"

    /** Testy: podmiana klienta (krótkie limity – sprawdzenie serwera nie może blokować ekranu). */
    @Volatile var http: okhttp3.OkHttpClient = Repository.http.newBuilder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS).readTimeout(2500, TimeUnit.MILLISECONDS).callTimeout(3, TimeUnit.SECONDS).build()

    /** Adres strumienia HTTP dla źródła P2P (indeks pliku Stremio jest liczony od 0, TorrServer – od 1). */
    fun streamUrl(s: Server, infoHash: String, fileIdx: Int, name: String = "video"): String {
        val idx = fileIdx.coerceAtLeast(0)
        val base = s.base.trimEnd('/')
        return when (s.kind) {
            Kind.TORRSERVER -> "$base/stream/${Uri.encode(name.ifBlank { "video" })}?link=${infoHash.lowercase()}&index=${idx + 1}&play"
            Kind.STREMIO -> "$base/${infoHash.lowercase()}/$idx"
        }
    }

    /** Rodzaj serwera pod adresem (null – nie odpowiada). TorrServer: `/echo`; serwer Stremio: `/settings`. */
    suspend fun probe(base: String): Server? = withContext(Dispatchers.IO) {
        val b = base.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
        fun ok(path: String) = runCatching { http.newCall(okhttp3.Request.Builder().url(b + path).build()).execute().use { it.isSuccessful } }.getOrDefault(false)
        when {
            ok("/echo") -> Server(b, Kind.TORRSERVER)
            ok("/settings") -> Server(b, Kind.STREMIO)
            else -> null
        }
    }

    /** Serwer do użycia: zapisany adres albo (pusty) TorrServer / serwer Stremio na tym urządzeniu. */
    suspend fun find(): Server? {
        val custom = runCatching { Prefs.torrentServer }.getOrDefault("").trim()
        if (custom.isNotEmpty()) return probe(custom)
        return probe(TORRSERVER_LOCAL) ?: probe(STREMIO_LOCAL)
    }

    /** Źródło P2P zamienione na zwykłe źródło HTTP przez serwer (dane torrenta zostają – np. wybór następnego odcinka). */
    fun asHttp(s: StremioStream, server: Server): StremioStream =
        s.copy(url = streamUrl(server, s.infoHash, s.fileIdx, s.filename.ifBlank { s.headline }))
}
