package pl.iptv.tv.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object Repository {
    const val DEFAULT_UA = "IptvTv/1.0 (Android TV) ExoPlayer"
    private lateinit var app: Context

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true)
            .build()
    }

    /** Lista z pliku, bez edycji. */
    @Volatile private var raw: List<Channel> = emptyList()
    /** Wszystkie kanały po edycji (także ukryte) – dla edytora. */
    @Volatile var allChannels: List<Channel> = emptyList(); private set
    /** Kanały widoczne. */
    @Volatile var channels: List<Channel> = emptyList(); private set
    @Volatile var epg: EpgData = EpgData.EMPTY; private set
    private var byKey: Map<String, Channel> = emptyMap()
    private var edits = EditStore()

    const val ALL = "Wszystkie"
    const val FAV = "★ Ulubione"
    const val RECENT = "🕘 Ostatnio oglądane"
    const val SEARCH = "🔍 Wyniki wyszukiwania"

    fun init(ctx: Context) {
        app = ctx.applicationContext; Prefs.init(app)
        edits = runCatching { EditStore.fromJson(editsFile.readText()) }.getOrDefault(EditStore())
        Reminders.init(File(app.filesDir, "reminders.json"))
        Recordings.init(File(app.filesDir, "recordings.json"))
        Playlists.migrate()
    }

    private fun suffix(id: String = Prefs.activePlaylist) = if (id == Playlists.MAIN) "" else "_$id"
    private val playlistCache get() = File(app.filesDir, "playlist${suffix()}.m3u")
    private val epgCache get() = File(app.filesDir, "epg${suffix()}.xml.dat")
    private val epgBin get() = File(app.filesDir, "epg${suffix()}.bin")
    private val editsFile get() = File(app.filesDir, "edits.json")

    val userAgent get() = Prefs.userAgent.ifBlank { DEFAULT_UA }

    fun byKey(key: String): Channel? = byKey[key]

    /** Prawdziwe grupy (z uwzględnieniem edycji), tylko widoczne kanały. */
    val realGroups: List<String> get() = channels.map { it.group }.distinct()

    /** Grupy do wyświetlenia na liście, z grupami specjalnymi zależnie od ustawień. */
    fun groups(): List<String> = allGroups().filter { it !in Prefs.groupHidden }.ifEmpty { listOf(ALL) }

    /** Wszystkie kategorie (także ukryte) w kolejności ustawionej przez użytkownika. */
    fun allGroups(): List<String> {
        val base = buildList {
            if (Prefs.showFavGroup && favorites().isNotEmpty()) add(FAV)
            if (Prefs.showRecentGroup && recent().isNotEmpty()) add(RECENT)
            add(ALL)
            addAll(realGroups)
        }
        val order = Prefs.groupOrder
        return (order.filter { it in base } + base.filter { it !in order })
    }

    fun moveGroup(g: String, delta: Int) {
        val l = allGroups().toMutableList(); val i = l.indexOf(g); val j = (i + delta).coerceIn(0, l.size - 1)
        if (i < 0 || i == j) return
        l.add(j, l.removeAt(i)); Prefs.groupOrder = l
    }

    fun toggleGroupVisible(g: String) {
        Prefs.groupHidden = if (g in Prefs.groupHidden) Prefs.groupHidden - g else Prefs.groupHidden + g
    }

    fun resetGroupLayout() { Prefs.groupOrder = emptyList(); Prefs.groupHidden = emptyList() }

    fun channelsFor(group: String): List<Channel> = when (group) {
        ALL -> channels
        FAV -> favorites()
        RECENT -> recent()
        else -> channels.filter { it.group == group }
    }

    fun search(q: String): List<Channel> {
        val t = q.trim()
        if (t.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        return channels.filter {
            it.name.contains(t, true) || it.number.toString() == t ||
                (current(it, now)?.title?.contains(t, true) == true)
        }
    }

    // ---------------- Ulubione / ostatnie ----------------

    fun isFav(ch: Channel) = ch.key in Prefs.favorites
    fun toggleFav(ch: Channel): Boolean {
        val l = Prefs.favorites.toMutableList()
        val added = if (l.remove(ch.key)) false else { l.add(ch.key); true }
        Prefs.favorites = l
        return added
    }
    fun favorites(): List<Channel> = Prefs.favorites.mapNotNull { k -> byKey[k]?.takeIf { !it.hidden } }

    fun addRecent(ch: Channel) {
        val l = Prefs.recent.toMutableList()
        l.remove(ch.key); l.add(0, ch.key)
        Prefs.recent = l.take(50)
    }
    fun recent(limit: Int = Prefs.recentLimit): List<Channel> =
        Prefs.recent.mapNotNull { k -> byKey[k]?.takeIf { !it.hidden } }.take(limit)
    fun clearRecent() { Prefs.recent = emptyList() }

    // ---------------- Edycja listy ----------------

    fun override(key: String): ChannelOverride = edits.items[key]?.copy() ?: ChannelOverride()

    fun edit(key: String, block: (ChannelOverride) -> Unit) {
        val o = edits.items.getOrPut(key) { ChannelOverride() }
        block(o)
        val ch = allChannels.firstOrNull { it.key == key }
        if (ch != null) {
            if (o.name == ch.origName) o.name = null
            if (o.group == ch.origGroup) o.group = null
        }
        if (o.isEmpty()) edits.items.remove(key)
        commitEdits()
    }

    /** Przenosi kanał [key] na pozycję kanału [targetKey] (w pełnej kolejności). */
    fun moveChannel(key: String, targetKey: String) {
        val newOrder = ChannelEdits.move(allChannels.map { it.key }, key, targetKey)
        edits.order.clear(); edits.order.addAll(newOrder)
        commitEdits()
    }

    fun renameGroup(old: String, new: String) {
        allChannels.filter { it.group == old }.forEach { c ->
            edits.items.getOrPut(c.key) { ChannelOverride() }.group = new.takeIf { it != c.origGroup }
        }
        edits.items.entries.removeAll { it.value.isEmpty() }
        commitEdits()
    }

    fun setGroupHidden(group: String, hidden: Boolean) {
        allChannels.filter { it.group == group }.forEach { c ->
            edits.items.getOrPut(c.key) { ChannelOverride() }.hidden = hidden
        }
        edits.items.entries.removeAll { it.value.isEmpty() }
        commitEdits()
    }

    fun resetChannel(key: String) { edits.items.remove(key); commitEdits() }

    fun resetAllEdits() { edits = EditStore(); commitEdits() }

    /** Pusta lista kanałów w pamięci (np. po usunięciu wszystkich list). */
    fun clearPlaylist() { raw = emptyList(); epg = EpgData.EMPTY; rebuild() }

    /** Po przywróceniu kopii zapasowej: edycje, aktywna lista i EPG wczytane od nowa (bez restartu aplikacji). */
    suspend fun reloadAfterRestore(): Int {
        edits = runCatching { EditStore.fromJson(editsFile.readText()) }.getOrDefault(EditStore())
        raw = emptyList(); epg = EpgData.EMPTY; rebuild()
        Playlists.active()?.let { Playlists.applyToPrefs(it) }
        val src = Prefs.playlistSource
        if (!loadPlaylistFromCache() && src.isNotBlank()) loadPlaylist(src)
        if (!loadEpgFromCache() && Prefs.effectiveEpgUrl.isNotBlank()) runCatching { loadEpg() }
        return allChannels.size
    }

    private fun commitEdits() {
        runCatching { editsFile.writeText(edits.toJson()) }
        rebuild()
    }

    /** Przełącza aktywną listę: wczytuje jej kopię (lub pobiera) i EPG. */
    suspend fun switchPlaylist(id: String): Int {
        val p = Playlists.all().firstOrNull { it.id == id } ?: throw IOException("Brak listy")
        Playlists.captureActive()
        Prefs.activePlaylist = id
        val keepEpg = Prefs.effectiveEpgUrl
        Playlists.applyToPrefs(p); Prefs.playlistEpgUrl = ""
        // lista bez własnego EPG zachowuje dotychczasowy adres (nie trzeba go wpisywać ponownie)
        if (Prefs.epgUrl.isBlank() && keepEpg.isNotBlank()) { Prefs.epgUrl = keepEpg; Playlists.update(p.copy(epg = keepEpg)) }
        raw = emptyList(); epg = EpgData.EMPTY; rebuild()
        if (!loadPlaylistFromCache()) loadPlaylist(p.source)
        if (!loadEpgFromCache() && Prefs.effectiveEpgUrl.isNotBlank()) runCatching { loadEpg() }
        return allChannels.size
    }

    /** Usuwa pliki pamięci podręcznej listy. */
    fun deletePlaylistFiles(id: String) {
        File(app.filesDir, "playlist${suffix(id)}.m3u").delete(); File(app.filesDir, "epg${suffix(id)}.xml.dat").delete()
        File(app.filesDir, "epg${suffix(id)}.bin").delete()
    }

    /** Ponownie nakłada edycje i ustawienia catch-up (np. po zmianie w ustawieniach). */
    fun reapply() = rebuild()

    private fun withCatchupDefault(c: Channel): Channel {
        val t = Prefs.catchupDefaultType
        if (t.isBlank() || (c.hasCatchup && !Prefs.catchupForce)) return c
        return c.copy(
            catchupType = t,
            catchupSource = if (Prefs.catchupForce || c.catchupSource.isBlank()) Prefs.catchupDefaultSource else c.catchupSource,
            catchupDays = Prefs.catchupDefaultDays,
        )
    }

    /** Zmienia się przy każdej przebudowie listy (ekrany odświeżają się tylko, gdy coś się zmieniło). */
    @Volatile var version = 0; private set

    private fun rebuild() {
        version++
        val all = ChannelEdits.apply(raw, edits).map(::withCatchupDefault)
        allChannels = all
        channels = all.filter { !it.hidden && !Parental.hideFromLists(it) }
        byKey = all.associateBy { it.key }
        mapEpg()
    }

    /** Po zmianie mapowania EPG: jeśli brakuje danych nowego kanału – ponowne parsowanie cache. */
    suspend fun refreshEpgMapping() {
        val missing = allChannels.any { c ->
            val o = c.epgOverride
            !o.isNullOrEmpty() && epg.programmes[o] == null && epg.idByLower[o.lowercase()] == null
        }
        if (missing) loadEpgFromCache() else mapEpg()
    }

    // ---------------- Playlista ----------------

    /** Wczytuje playlistę z dowolnego źródła i zapisuje kopię w cache. */
    suspend fun loadPlaylist(source: String): Int = withContext(Dispatchers.IO) {
        val bytes = openSource(source).use { it.readBytes() }
        val pl = M3uParser.parse(String(bytes, Charsets.UTF_8))
        if (pl.channels.isEmpty()) throw IOException("Lista nie zawiera kanałów")
        playlistCache.writeBytes(bytes)
        Prefs.playlistSource = source
        Playlists.syncActive(source)
        applyPlaylist(pl)
        pl.channels.size
    }

    suspend fun loadPlaylistFromCache(): Boolean = withContext(Dispatchers.IO) {
        if (!playlistCache.exists()) return@withContext false
        val pl = runCatching { playlistCache.bufferedReader().use { M3uParser.parse(it) } }.getOrNull()
            ?: return@withContext false
        applyPlaylist(pl)
        true
    }

    private fun applyPlaylist(pl: Playlist) {
        Prefs.playlistEpgUrl = pl.epgUrls.firstOrNull() ?: ""
        raw = pl.channels
        rebuild()
    }

    /** Adres z podstawionym tokenem aktywnej listy ({token}). */
    fun withToken(s: String) = s.replace("{token}", Playlists.active()?.token.orEmpty())

    private fun openSource(source: String): InputStream {
        val s = withToken(source.trim())
        return when {
            s.startsWith("http://", true) || s.startsWith("https://", true) -> download(s)
            s.startsWith("content://") -> app.contentResolver.openInputStream(Uri.parse(s))
                ?: throw IOException("Nie można otworzyć pliku")
            s.startsWith("file://") -> File(Uri.parse(s).path!!).inputStream()
            else -> File(s).inputStream()
        }
    }

    private fun download(url: String): InputStream {
        val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) { resp.close(); throw IOException("HTTP ${resp.code} dla $url") }
        return resp.body!!.byteStream()
    }

    // ---------------- EPG ----------------

    private fun window(): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        // przewodnik pokazuje 7 dni wstecz; dłużej, jeśli archiwum kanału jest dłuższe
        val cu = allChannels.maxOfOrNull { it.catchupDays } ?: 0
        // słabsze urządzenia: tylko zakres archiwum (min. 1 dzień) i 3 dni naprzód – mniej pamięci i szybsze wczytywanie
        val back = if (Prefs.lowPerf) cu.coerceIn(1, 14) else cu.coerceIn(7, 14)
        val ahead = if (Prefs.lowPerf) Prefs.lowEpgAhead.coerceIn(1, 7) else 7
        return (now - back * 86_400_000L - 3_600_000L) to (now + ahead * 86_400_000L)
    }

    /** Wykonuje [block] z niskim priorytetem wątku (parsowanie EPG nie zabiera CPU odtwarzaczowi i interfejsowi). */
    private inline fun <T> lowPriority(block: () -> T): T {
        val tid = android.os.Process.myTid(); val old = runCatching { android.os.Process.getThreadPriority(tid) }.getOrDefault(0)
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
        try { return block() } finally { runCatching { android.os.Process.setThreadPriority(old) } }
    }

    /** Kilka adresów EPG oddzielonych spacją, przecinkiem, średnikiem lub nową linią. */
    fun epgUrls(url: String = Prefs.effectiveEpgUrl): List<String> =
        // pojedynczy plik ze spacją w ścieżce lub adres content://
        if (url.trim().startsWith("content://") || (url.isNotBlank() && File(url.trim()).exists())) listOf(url.trim()) else url.split(Regex("[\\s,;]+")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun epgCacheFile(i: Int) = if (i == 0) epgCache else File(epgCache.path + ".$i")

    /** Łączy dane z kilku plików XMLTV (ten sam kanał: programy z obu, bez powtórzeń). */
    private fun merge(parts: List<EpgData>): EpgData {
        if (parts.size == 1) return parts[0]
        val progs = HashMap<String, List<Programme>>()
        for (d in parts) for ((k, v) in d.programmes) {
            val old = progs[k]
            progs[k] = if (old == null) v else (old + v).distinctBy { it.start }.sortedBy { it.start }
        }
        fun <V> m(f: (EpgData) -> Map<String, V>) = HashMap<String, V>().apply { parts.asReversed().forEach { putAll(f(it)) } }
        return EpgData(progs, m { it.idByLower }, m { it.idByName }, m { it.icons }, m { it.channelNames })
    }

    /** Pobieranie EPG podczas oglądania jest spowalniane, by nie zabierać łącza odtwarzaczowi (słabsze urządzenia). */
    private fun throttled(input: java.io.InputStream): java.io.InputStream {
        val playing = pl.iptv.tv.ui.Playback.isPlayingNow
        if (!playing) return input
        val bps = if (Prefs.lowPerf) Prefs.lowEpgKbps.coerceIn(100, 5000) * 1000L else 2_500_000L
        return object : java.io.FilterInputStream(input) {
            private val t0 = System.nanoTime(); private var n = 0L
            private fun pace() { val want = n * 1_000_000_000L / bps; val dt = System.nanoTime() - t0; if (want > dt) Thread.sleep((want - dt) / 1_000_000) }
            override fun read(b: ByteArray, off: Int, len: Int): Int { val r = super.read(b, off, minOf(len, 16 * 1024)); if (r > 0) { n += r; pace() }; return r }
            override fun read(): Int { val r = super.read(); if (r >= 0) { n++; if (n % 16384 == 0L) pace() }; return r }
        }
    }

    private fun maxDesc() = if (Prefs.lowPerf) Prefs.lowDescLen.coerceAtLeast(50) else Int.MAX_VALUE

    /** Znacznik „trwa wczytywanie EPG” – jeśli aplikacja padnie w trakcie, następny start to wykryje ([epgCrashed]). */
    private inline fun <T> guardedParse(block: () -> T): T {
        Prefs.epgParsing = true
        try { return block().also { Prefs.epgCrashes = 0 } } finally { Prefs.epgParsing = false }
    }

    /** Wywoływane przy starcie aplikacji: poprzednie wczytywanie EPG zakończyło się awarią (brak pamięci). */
    fun epgCrashed(): Boolean {
        if (!Prefs.epgParsing) return false
        Prefs.epgParsing = false
        Prefs.epgCrashes = Prefs.epgCrashes + 1
        Prefs.lowPerf = true                       // od razu lżejsze wczytywanie (krótszy zakres, krótsze opisy)
        return true
    }

    /** Po dwóch awariach z rzędu EPG nie jest wczytywane automatycznie (bez pętli: zacinanie → zamknięcie → od nowa). */
    val epgAutoBlocked get() = Prefs.epgCrashes >= 2

    suspend fun loadEpg(url: String = Prefs.effectiveEpgUrl): Int = withContext(Dispatchers.IO) { lowPriority {
        val urls = epgUrls(url)
        if (urls.isEmpty()) throw IOException("Brak adresu EPG")
        // słabsze urządzenie: stare dane zwalniane przed wczytaniem nowych (bez podwójnego zużycia pamięci)
        if (Prefs.lowPerf && epg.programmes.isNotEmpty()) { epg = EpgData.EMPTY; mapEpg() }
        val (from, to) = window()
        val parts = ArrayList<EpgData>()
        var lastErr: Throwable? = null
        urls.forEachIndexed { i, u ->
            val tmp = File(app.filesDir, "epg.tmp")
            runCatching {
                openSource(u).use { input -> throttled(input).use { t -> tmp.outputStream().use { t.copyTo(it, 64 * 1024) } } }
                parts += guardedParse { tmp.inputStream().use { XmltvParser.parse(it, allChannels, from, to, maxDesc()) } }
                epgCacheFile(i).delete(); tmp.renameTo(epgCacheFile(i))
            }.onFailure { lastErr = it; tmp.delete() }
        }
        if (parts.isEmpty()) throw (lastErr ?: IOException("Brak danych EPG"))
        var i = urls.size; while (epgCacheFile(i).exists()) { epgCacheFile(i).delete(); i++ }
        val data = merge(parts)
        Prefs.lastEpgUpdate = System.currentTimeMillis()
        epg = data
        mapEpg()
        runCatching { EpgBinary.write(epgBin, data, EpgBinary.signature(allChannels)) }
        data.programmes.values.sumOf { it.size }
    } }

    suspend fun loadEpgFromCache(): Boolean = withContext(Dispatchers.IO) { lowPriority {
        val (from, to) = window()
        // szybka ścieżka: gotowe, przetworzone EPG (bez parsowania XML)
        EpgBinary.read(epgBin, EpgBinary.signature(allChannels), from, to)?.let { epg = it; mapEpg(); return@withContext true }
        if (!epgCache.exists()) return@withContext false
        val files = generateSequence(0) { it + 1 }.map { epgCacheFile(it) }.takeWhile { it.exists() }.toList()
        val data = runCatching { merge(files.map { f -> guardedParse { f.inputStream().use { XmltvParser.parse(it, allChannels, from, to, maxDesc()) } } }) }
            .getOrNull() ?: return@withContext false
        epg = data
        mapEpg()
        runCatching { EpgBinary.write(epgBin, data, EpgBinary.signature(allChannels)) }
        true
    } }

    private fun mapEpg() {
        val e = epg
        allChannels.forEach { ch ->
            ch.epgKey = e.keyFor(ch)
            ch.epgIcon = ch.epgKey?.let { e.icons[it] }
        }
        episodeIndex = null
        prepareIndexAsync()
        if (e.programmes.isNotEmpty() && ::app.isInitialized) {
            Reminders.expandRules(channels, ::programmes, System.currentTimeMillis())
            pl.iptv.tv.reminders.ReminderScheduler.schedule(app)
        }
    }

    // ---------------- wyszukiwanie w programie / odcinki ----------------

    data class Hit(val channel: Channel, val programme: Programme)

    enum class When { ALL, ARCHIVE, NOW, UPCOMING }

    /** Szuka w tytułach i tytułach odcinków (opcjonalnie w opisach) na wszystkich widocznych kanałach. */
    fun searchProgrammes(q: String, inDesc: Boolean, w: When = When.ALL, limit: Int = 400): List<Hit> {
        val t = q.trim()
        if (t.length < 2) return emptyList()
        val now = System.currentTimeMillis()
        val out = ArrayList<Hit>()
        for (ch in channels) for (p in programmes(ch)) {
            val ok = when (w) {
                When.ALL -> true
                When.ARCHIVE -> p.end <= now && isInArchive(ch, p, now)
                When.NOW -> p.isNow(now)
                When.UPCOMING -> p.start > now
            }
            if (!ok) continue
            if (p.title.contains(t, true) || p.subTitle.contains(t, true) || p.episode.equals(t, true) ||
                (inDesc && p.desc.contains(t, true))) out += Hit(ch, p)
        }
        // ta sama audycja na kilku wersjach kanału (HD/SD) zostaje – użytkownik wybiera kanał
        return out.sortedBy { it.programme.start }.let { if (it.size > limit) it.take(limit) else it }
    }

    fun isInArchive(ch: Channel, p: Programme, now: Long = System.currentTimeMillis()) =
        ch.hasCatchup && p.start <= now && p.start >= now - ch.catchupDays * 86_400_000L

    @Volatile private var episodeIndex: Map<String, List<Hit>>? = null

    private fun buildIndex(): Map<String, List<Hit>> {
        val m = HashMap<String, ArrayList<Hit>>()
        for (ch in channels) for (p in programmes(ch)) m.getOrPut(Series.key(p.title)) { ArrayList() }.add(Hit(ch, p))
        m.values.forEach { l -> l.sortBy { it.programme.start } }
        return m
    }

    @Synchronized private fun index(): Map<String, List<Hit>> = episodeIndex ?: buildIndex().also { episodeIndex = it }

    /** Buduje indeks odcinków w tle (wywoływane po wczytaniu EPG), by nie blokować interfejsu. */
    fun prepareIndexAsync() {
        if (epg.programmes.isEmpty()) return
        Thread({ runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST) }; runCatching { index() } }, "EpisodeIndex").start()
    }

    /** Wszystkie emisje tej samej serii (wszystkie kanały), posortowane wg czasu. */
    fun episodes(p: Programme): List<Hit> = index()[Series.key(p.title)] ?: emptyList()

    /** Następny odcinek tylko jeśli indeks jest gotowy – do opisów odświeżanych przy przewijaniu. */
    fun nextEpisodeIfReady(ch: Channel, p: Programme): Hit? = if (episodeIndex == null) null else nextEpisode(ch, p)

    /**
     * Następny odcinek po [p]: pierwsza późniejsza emisja tej serii, która nie jest
     * tym samym odcinkiem. Preferuje ten sam kanał przy równoczesnych emisjach.
     */
    fun nextEpisode(ch: Channel, p: Programme): Hit? {
        val from = maxOf(p.start, System.currentTimeMillis() - 1)
        val later = episodes(p).filter { it.programme.start > from && Series.isDifferentEpisode(p, it.programme) }
        val first = later.firstOrNull() ?: return null
        return later.firstOrNull { it.programme.start == first.programme.start && it.channel.key == ch.key } ?: first
    }

    fun programmes(ch: Channel): List<Programme> = ch.epgKey?.let { epg.programmes[it] } ?: emptyList()

    /** Indeks pierwszego programu kończącego się po [t] (lista posortowana wg startu). */
    private fun firstEndingAfter(l: List<Programme>, t: Long): Int {
        var lo = 0; var hi = l.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (l[mid].end <= t) lo = mid + 1 else hi = mid }
        return lo
    }

    /** Programy nachodzące na okno [from, to) – wyszukiwanie binarne zamiast przeglądania całej listy. */
    fun programmesIn(ch: Channel, from: Long, to: Long): List<Programme> {
        val l = programmes(ch); var i = firstEndingAfter(l, from); val out = ArrayList<Programme>()
        while (i < l.size && l[i].start < to) { out += l[i]; i++ }
        return out
    }

    fun current(ch: Channel, now: Long = System.currentTimeMillis()): Programme? {
        val l = programmes(ch); val i = firstEndingAfter(l, now)
        return l.getOrNull(i)?.takeIf { it.start <= now }
    }

    fun next(ch: Channel, now: Long = System.currentTimeMillis()): Programme? {
        val l = programmes(ch); var i = firstEndingAfter(l, now)
        while (i < l.size && l[i].start < now) i++
        return l.getOrNull(i)
    }

    // ---------------- Picony ----------------

    /**
     * Kolejność: tvg-logo (absolutny URL, lub względny doklejany do adresu picon),
     * szablon picon ({id}, {name}, {name_raw}), ikona z EPG.
     */
    fun piconFor(ch: Channel): String? {
        val base = Prefs.piconUrl.trim()
        val logo = ch.logo
        if (logo.startsWith("http", true) || logo.startsWith("file:") || logo.startsWith("content:")) return logo
        if (base.isNotEmpty()) {
            if (logo.isNotEmpty()) return base.trimEnd('/') + "/" + logo.trimStart('/')
            val nameKey = ch.tvgName.ifBlank { ch.name }
            val safe = nameKey.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), "")
            return if (base.contains('{')) {
                base.replace("{id}", enc(ch.tvgId))
                    .replace("{name_raw}", enc(nameKey))
                    .replace("{name}", enc(safe))
            } else base.trimEnd('/') + "/" + enc(safe) + ".png"
        }
        return ch.epgIcon
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ---------------- Tło ----------------
    val bgImageFile: File get() = File(app.filesDir, "background.img")

    /** Zapisuje obraz tła z linku http lub z content:// */
    suspend fun saveBackground(source: String) = withContext(Dispatchers.IO) {
        val tmp = File(app.filesDir, "background.tmp")
        openSource(source).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (tmp.length() == 0L) throw IOException("Pusty plik")
        tmp.renameTo(bgImageFile)
        Prefs.bgImageOn = true
    }
}
