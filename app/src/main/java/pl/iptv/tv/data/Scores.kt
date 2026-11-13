package pl.iptv.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Wyniki meczów obserwowanych lig i drużyn.
 * Źródło domyślne: ESPN (bez klucza, bez polskich lig), opcjonalnie API-Football (klucz użytkownika).
 */
object Scores {
    var espnBase = "https://site.api.espn.com/apis/site/v2/sports/soccer"
    var afBase = "https://v3.football.api-sports.io"
    var tsdbBase = "https://www.thesportsdb.com/api/v1/json/123"

    /** [espn] – identyfikator ligi w ESPN (null = tylko API-Football), [af] – identyfikator w API-Football. */
    /** [tsdb] – rozgrywki z TheSportsDB (dart), wyniki dnia turnieju. */
    data class League(val espn: String?, val af: Int, val name: String, val tsdb: Int = 0) {
        val id get() = if (tsdb > 0) "tsdb:$tsdb" else espn ?: "af:$af"
    }

    val LEAGUES = listOf(
        League(null, 106, "🇵🇱 Ekstraklasa"), League(null, 107, "🇵🇱 1 Liga"), League(null, 108, "🇵🇱 Puchar Polski"),
        League("uefa.champions", 2, "🏆 Liga Mistrzów"), League("uefa.europa", 3, "🏆 Liga Europy"), League("uefa.europa.conf", 848, "🏆 Liga Konferencji"),
        League("eng.1", 39, "🏴 Premier League"), League("esp.1", 140, "🇪🇸 LaLiga"), League("ger.1", 78, "🇩🇪 Bundesliga"),
        League("ita.1", 135, "🇮🇹 Serie A"), League("fra.1", 61, "🇫🇷 Ligue 1"), League("ned.1", 88, "🇳🇱 Eredivisie"),
        League("por.1", 94, "🇵🇹 Liga Portugal"), League("tur.1", 203, "🇹🇷 Süper Lig"), League("eng.2", 40, "🏴 Championship"),
        League("sco.1", 179, "🏴 Szkocja Premiership"), League("bel.1", 144, "🇧🇪 Belgia Pro League"), League("usa.1", 253, "🇺🇸 MLS"),
        League("ksa.1", 307, "🇸🇦 Saudi Pro League"), League("eng.fa", 45, "🏴 Puchar Anglii"), League("esp.copa_del_rey", 143, "🇪🇸 Puchar Króla"),
        League("ger.dfb_pokal", 81, "🇩🇪 Puchar Niemiec"), League("fifa.world", 1, "🌍 Mistrzostwa Świata"), League("fifa.worldq.uefa", 32, "🌍 El. MŚ (Europa)"),
        League("uefa.euro", 4, "🇪🇺 Euro"), League("uefa.euroq", 960, "🇪🇺 El. Euro"), League("uefa.nations", 5, "🇪🇺 Liga Narodów"),
        League("fifa.friendly", 10, "🤝 Mecze towarzyskie"),
        League(null, 0, "🎯 Dart – PDC", tsdb = 4554), League(null, 0, "🎯 Dart – WDF/BDO", tsdb = 4561),
    )
    fun league(id: String) = LEAGUES.firstOrNull { it.id == id }

    /** Obserwowana liga ([team] == null) albo drużyna w lidze. */
    data class Follow(val league: String, val team: String? = null, val name: String) {
        val key get() = league + "|" + (team ?: "")
    }

    var follows: List<Follow>
        get() = runCatching {
            val a = JSONArray(Prefs.scoreFollowJson)
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Follow(o.getString("l"), o.optString("t").ifBlank { null }, o.getString("n")) } }
        }.getOrDefault(emptyList())
        set(v) { Prefs.scoreFollowJson = JSONArray(v.map { JSONObject().put("l", it.league).put("t", it.team ?: "").put("n", it.name) }).toString() }

    fun isFollowed(league: String, team: String? = null) = follows.any { it.league == league && it.team == team }
    fun follow(f: Follow) { if (!isFollowed(f.league, f.team)) follows = follows + f }
    fun unfollow(f: Follow) { follows = follows.filter { it.key != f.key } }
    fun move(f: Follow, d: Int) {
        val l = follows.toMutableList(); val i = l.indexOfFirst { it.key == f.key }; val j = (i + d).coerceIn(0, l.size - 1)
        if (i < 0 || i == j) return; l.add(j, l.removeAt(i)); follows = l
    }

    val useAf get() = Prefs.apiFootballKey.isNotBlank()
    /** Ligi dostępne w bieżącym źródle. */
    fun available() = LEAGUES.filter { it.tsdb > 0 || (it.af > 0 && useAf) || it.espn != null }

    enum class State { PRE, LIVE, POST }
    data class Match(
        val id: String, val league: String, val leagueName: String, val home: String, val away: String,
        val homeId: String, val awayId: String, val hs: Int?, val aws: Int?, val state: State, val clock: String, val start: Long,
        val events: List<String> = emptyList(), val homeLogo: String = "", val awayLogo: String = "",
        /** Dart: kolejność w turnieju (finał najwyżej), [away] puste = zapowiedź turnieju. */
        val order: Int = 0,
        /** Pełne nazwy drużyn (do szukania w programie TV), np. „Tottenham Hotspur” obok skrótu „Spurs”. */
        val homeFull: String = "", val awayFull: String = "",
    ) {
        val score get() = if (hs == null || aws == null || state == State.PRE) "–:–" else "$hs:$aws"
    }

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val rb = Request.Builder().url(url); headers.forEach { (k, v) -> rb.header(k, v) }
        return Repository.http.newCall(rb.build()).execute().use { r -> if (!r.isSuccessful) error("HTTP ${r.code}"); r.body!!.string() }
    }

    /** Czas ISO 8601: "2026-11-07T12:30Z", "…T12:30:00+01:00", "…T12:30:00Z". */
    fun isoTime(s: String): Long {
        val t = s.trim().replace(Regex("([+-]\\d\\d):(\\d\\d)$"), "$1$2").replace(Regex("Z$"), "+0000")
        for (p in listOf("yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mmZ", "yyyy-MM-dd'T'HH:mm:ss.SSSZ")) {
            runCatching { return SimpleDateFormat(p, Locale.US).apply { isLenient = false }.parse(t)!!.time }
        }
        return 0L
    }

    // ---------------- drużyny w lidze (do wyboru w ustawieniach) ----------------

    suspend fun teams(l: League): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        if (l.tsdb > 0) error("W darcie obserwuj całe rozgrywki (Obserwuj ligę…)")
        if (useAf) {
            val season = currentSeason()
            val o = JSONObject(get("$afBase/teams?league=${l.af}&season=$season", afHeaders())); afError(o)
            val a = o.getJSONArray("response")
            (0 until a.length()).map { a.getJSONObject(it).getJSONObject("team").let { t -> t.getInt("id").toString() to t.getString("name") } }
        } else {
            val a = JSONObject(get("$espnBase/${l.espn}/teams")).getJSONArray("sports").getJSONObject(0)
                .getJSONArray("leagues").getJSONObject(0).getJSONArray("teams")
            (0 until a.length()).map { a.getJSONObject(it).getJSONObject("team").let { t -> t.getString("id") to t.getString("displayName") } }
        }.sortedBy { it.second.lowercase() }
    }

    /** Sezon europejski: od lipca nowy (np. listopad 2026 -> 2026). */
    private fun currentSeason(): Int { val c = java.util.Calendar.getInstance(); return c.get(1) - if (c.get(2) < 6) 1 else 0 }
    private fun afHeaders() = mapOf("x-apisports-key" to Prefs.apiFootballKey)
    private fun afError(o: JSONObject) {
        val e = o.opt("errors")
        val msg = when (e) { is JSONObject -> e.keys().asSequence().map { e.optString(it) }.joinToString("; "); is JSONArray -> if (e.length() > 0) e.toString() else ""; else -> "" }
        if (msg.isNotBlank()) error("API-Football: $msg")
    }

    // ---------------- mecze ----------------

    @Volatile var last: List<Match> = emptyList(); private set
    @Volatile var lastAt = 0L; private set

    /** Dzisiejsze mecze obserwowanych lig i drużyn (na żywo, potem nadchodzące, potem zakończone). */
    suspend fun fetch(): List<Match> = withContext(Dispatchers.IO) {
        val f = follows
        if (f.isEmpty()) return@withContext emptyList<Match>().also { last = it; lastAt = System.currentTimeMillis() }
        val leagues = f.mapNotNull { league(it.league) }.distinct()
        val foot = leagues.filter { it.tsdb == 0 }
        val all = (if (useAf) fetchAf(foot) else foot.filter { it.espn != null }.flatMap { l -> runCatching { fetchEspn(l) }.getOrDefault(emptyList()) }) +
            leagues.filter { it.tsdb > 0 }.flatMap { l -> runCatching { fetchTsdb(l) }.getOrDefault(emptyList()) }
        val wanted = all.filter { m -> f.any { it.league == m.league && (it.team == null || it.team == m.homeId || it.team == m.awayId) } }
        val sorted = wanted.distinctBy { it.id }.sortedWith(compareBy({ when (it.state) { State.LIVE -> 0; State.PRE -> 1; State.POST -> 2 } }, { if (it.state == State.POST) -it.order else 0 }, { it.start }))
        last = sorted; lastAt = System.currentTimeMillis()
        liveByChannel = buildLive(sorted)
        sorted
    }

    private fun day(offset: Int = 0) = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(System.currentTimeMillis() + offset * 86_400_000L))

    fun fetchEspn(l: League): List<Match> {
        val o = JSONObject(get("$espnBase/${l.espn}/scoreboard?dates=${day()}"))
        val ev = o.optJSONArray("events") ?: return emptyList()
        return (0 until ev.length()).mapNotNull { i ->
            val e = ev.getJSONObject(i)
            val comp = e.getJSONArray("competitions").getJSONObject(0)
            val cs = comp.getJSONArray("competitors")
            val teams = (0 until cs.length()).map { cs.getJSONObject(it) }
            val h = teams.firstOrNull { it.optString("homeAway") == "home" } ?: teams.getOrNull(0) ?: return@mapNotNull null
            val a = teams.firstOrNull { it.optString("homeAway") == "away" } ?: teams.getOrNull(1) ?: return@mapNotNull null
            val st = e.getJSONObject("status"); val type = st.getJSONObject("type")
            val state = when (type.optString("state")) { "in" -> State.LIVE; "post" -> State.POST; else -> State.PRE }
            val names = mapOf(h.getJSONObject("team").getString("id") to h.getJSONObject("team").optString("shortDisplayName", h.getJSONObject("team").optString("displayName")),
                a.getJSONObject("team").getString("id") to a.getJSONObject("team").optString("shortDisplayName", a.getJSONObject("team").optString("displayName")))
            val det = comp.optJSONArray("details")
            val events = if (det == null) emptyList() else (0 until det.length()).mapNotNull { k ->
                val d = det.getJSONObject(k)
                val kind = when { d.optBoolean("scoringPlay") -> if (d.optBoolean("ownGoal")) "⚽ (sam.)" else if (d.optBoolean("penaltyKick")) "⚽ (k.)" else "⚽"
                    d.optBoolean("redCard") -> "🟥"; d.optBoolean("yellowCard") -> "🟨"; else -> return@mapNotNull null }
                val who = d.optJSONArray("athletesInvolved")?.optJSONObject(0)?.optString("displayName").orEmpty()
                "${d.optJSONObject("clock")?.optString("displayValue").orEmpty()} $kind $who (${names[d.optJSONObject("team")?.optString("id")].orEmpty()})".trim()
            }
            Match(e.getString("id"), l.id, l.name,
                h.getJSONObject("team").optString("shortDisplayName", h.getJSONObject("team").optString("displayName")),
                a.getJSONObject("team").optString("shortDisplayName", a.getJSONObject("team").optString("displayName")),
                h.getJSONObject("team").getString("id"), a.getJSONObject("team").getString("id"),
                h.optString("score").toIntOrNull(), a.optString("score").toIntOrNull(), state,
                when (state) { State.LIVE -> type.optString("shortDetail").ifBlank { st.optString("displayClock") }; State.POST -> "KONIEC"; else -> "" },
                isoTime(e.optString("date")), events,
                h.getJSONObject("team").optString("logo"), a.getJSONObject("team").optString("logo"),
                homeFull = h.getJSONObject("team").optString("displayName"), awayFull = a.getJSONObject("team").optString("displayName"))
        }
    }

    /** Dart (TheSportsDB): wyniki ostatniego dnia turnieju (od finału) i najbliższy turniej. */
    fun fetchTsdb(l: League): List<Match> {
        val out = ArrayList<Match>()
        fun events(path: String) = JSONObject(get("$tsdbBase/$path?id=${l.tsdb}")).optJSONArray("events")
        val day = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun time(o: JSONObject) = runCatching { day.parse(o.optString("strTimestamp"))!!.time }.getOrDefault(0L)
        events("eventspastleague.php")?.let { a -> for (i in 0 until a.length()) {
            val e = a.getJSONObject(i); var round = ""; var n = 0
            val lines = e.optString("strResult").replace("\r", "").lines()
            for (raw in lines) {
                val line = raw.trim().removePrefix("1 ").trim()
                if (line.isEmpty()) continue
                val m = Regex("^(.+?)\\s+(\\d+)\\s*-\\s*(\\d+)\\s+(.+)$").find(line)
                if (m == null) { round = plRound(line); continue }
                n++
                out += Match("${e.optString("idEvent")}-$n", l.id, "${l.name} • ${e.optString("strEvent")}" + (if (round.isNotEmpty()) " • $round" else ""),
                    m.groupValues[1], m.groupValues[4], "", "", m.groupValues[2].toInt(), m.groupValues[3].toInt(), State.POST, "KONIEC", time(e),
                    order = n)
            }
        } }
        events("eventsnextleague.php")?.let { a -> if (a.length() > 0) a.getJSONObject(0).let { e ->
            val t = time(e)
            out += Match(e.optString("idEvent"), l.id, l.name + (e.optString("strCity").takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""),
                e.optString("strEvent"), "", "", "", null, null, State.PRE, "", t)
        } }
        return out
    }

    private fun plRound(s: String) = when (s.lowercase()) {
        "final" -> "Finał"; "semi-finals", "semi finals" -> "Półfinały"; "quarter-finals", "quarter finals" -> "Ćwierćfinały"
        "last 16" -> "1/8 finału"; "last 32" -> "1/16 finału"; "last 64" -> "1/32 finału"; else -> s
    }

    private fun fetchAf(leagues: List<League>): List<Match> {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val o = JSONObject(get("$afBase/fixtures?date=$date&timezone=${TimeZone.getDefault().id}", afHeaders())); afError(o)
        val ids = leagues.associateBy { it.af }
        val a = o.getJSONArray("response")
        return (0 until a.length()).mapNotNull { i ->
            val r = a.getJSONObject(i)
            val l = ids[r.getJSONObject("league").getInt("id")] ?: return@mapNotNull null
            val fx = r.getJSONObject("fixture"); val st = fx.getJSONObject("status"); val short = st.optString("short")
            val state = when (short) { "1H", "HT", "2H", "ET", "BT", "P", "LIVE", "INT", "SUSP" -> State.LIVE; "FT", "AET", "PEN", "AWD", "WO" -> State.POST; else -> State.PRE }
            val t = r.getJSONObject("teams"); val g = r.getJSONObject("goals")
            Match(fx.getInt("id").toString(), l.id, l.name, t.getJSONObject("home").getString("name"), t.getJSONObject("away").getString("name"),
                t.getJSONObject("home").getInt("id").toString(), t.getJSONObject("away").getInt("id").toString(),
                if (g.isNull("home")) null else g.getInt("home"), if (g.isNull("away")) null else g.getInt("away"), state,
                when (state) { State.LIVE -> if (short == "HT") "Przerwa" else "${st.optInt("elapsed")}'"; State.POST -> "KONIEC"; else -> "" },
                fx.optLong("timestamp") * 1000, emptyList(),
                t.getJSONObject("home").optString("logo"), t.getJSONObject("away").optString("logo"))
        }
    }

    /** Nowe bramki względem poprzedniego stanu: komunikaty "⚽ GOL! A 1:0 B". */
    fun goals(before: List<Match>, now: List<Match>): List<String> = goalMatches(before, now).map { m -> "⚽ GOL!  ${m.home} ${m.hs}:${m.aws} ${m.away}" }

    /** Mecze piłkarskie, w których od poprzedniego odświeżenia padła bramka. */
    fun goalMatches(before: List<Match>, now: List<Match>): List<Match> {
        val prev = before.associateBy { it.id }
        return now.filter { m ->
            val p = prev[m.id] ?: return@filter false
            !m.league.startsWith("tsdb:") && m.state != State.PRE && (m.hs ?: 0) + (m.aws ?: 0) > (p.hs ?: 0) + (p.aws ?: 0)
        }
    }

    // ---------------- kanały przypisane do drużyn i lig ----------------

    fun teamKey(league: String, team: String) = "t:$league:$team"
    fun leagueKey(league: String) = "l:$league"

    private fun assignMap(): JSONObject = runCatching { JSONObject(Prefs.scoreChannelsJson) }.getOrDefault(JSONObject())
    fun assigned(key: String): List<String> = assignMap().optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
    fun assign(key: String, channelKeys: List<String>) {
        val m = assignMap(); if (channelKeys.isEmpty()) m.remove(key) else m.put(key, JSONArray(channelKeys)); Prefs.scoreChannelsJson = m.toString()
    }

    /** Kanały przypisane do drużyn meczu i jego ligi (bez powtórzeń, tylko istniejące na liście). */
    fun assignedFor(m: Match): List<Channel> =
        (assigned(teamKey(m.league, m.homeId)) + assigned(teamKey(m.league, m.awayId)) + assigned(leagueKey(m.league)))
            .distinct().mapNotNull { Repository.byKey(it) }.filter { !it.hidden }

    private fun fold(x: String): String = java.text.Normalizer.normalize(x.lowercase(Locale.ROOT).replace('ł', 'l'), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    // słowa wspólne dla wielu drużyn – nie rozpoznają drużyny (np. „United”: Leeds, Newcastle, Manchester)
    private val GENERIC = setOf("club", "football", "futbol", "calcio", "sport", "sports", "team", "the", "and", "fc", "cf", "sc", "ac", "afc", "ssc",
        "real", "united", "city", "town", "county", "rovers", "athletic", "atletico", "sporting", "olympique", "racing", "deportivo", "union", "borussia", "dynamo", "inter")
    /** Polskie nazwy używane w programie TV dla popularnych drużyn (EPG po polsku). */
    private val ALIASES = mapOf("munich" to "monachium munchen", "milan" to "mediolan", "lisbon" to "lizbona", "madrid" to "madryt",
        "rome" to "rzym", "turin" to "turyn", "naples" to "neapol", "cologne" to "kolonia", "paris saint germain" to "psg",
        "spurs" to "tottenham", "man utd" to "manchester", "man city" to "manchester", "wolves" to "wolverhampton", "nottm" to "nottingham")

    /** Słowa rozpoznające drużynę (bez ogólnych, min. 4 znaki; także polskie odpowiedniki). */
    fun teamKeys(vararg names: String): Set<String> {
        val out = HashSet<String>()
        for (n in names) {
            var f = fold(n); if (f.isBlank()) continue
            ALIASES.forEach { (k, v) -> if (f.contains(k)) f += " $v" }
            f.split(' ').filter { it.length >= 4 && it !in GENERIC }.forEach { out += it }
        }
        return out
    }

    private val SPORT = Regex("pilka|football|soccer|liga|league|premier|bundesliga|laliga|serie a|ligue|ekstraklasa|mecz|champions|uefa|puchar|cup|eredivisie|mundial|euro")

    /** Wynik na żywo przy kanale z transmisją (lista kanałów, panel, lista po OK): klucz kanału -> „⚽ Leeds 1:0 Spurs 45'”. */
    @Volatile var liveByChannel: Map<String, String> = emptyMap(); private set

    fun buildLive(ms: List<Match>): Map<String, String> {
        val out = HashMap<String, String>()
        for (m in ms) if (m.state == State.LIVE) {
            val chs = broadcasts(m).filter { it.sure }.map { it.channel } + assignedFor(m)
            chs.forEach { ch -> out.putIfAbsent(ch.key, "⚽ ${m.home} ${m.score} ${m.away}  ${m.clock}") }
        }
        return out
    }

    fun liveFor(ch: Channel): String? = if (Prefs.scoresEnabled) liveByChannel[ch.key] else null

    /** Całe słowo; dłuższe nazwy także w odmianie (np. „Barcelona” – „Barcelony”). */
    private fun hit(text: String, k: String) = " $k " in text || (k.length >= 6 && text.contains(" " + k.dropLast(1)))

    /** Kanał i audycja z meczem: obie drużyny w tytule/opisie (pewne) albo jedna drużyna + audycja piłkarska. */
    data class Broadcast(val channel: Channel, val programme: Programme, val sure: Boolean)

    fun broadcasts(m: Match, now: Long = System.currentTimeMillis()): List<Broadcast> {
        if (m.league.startsWith("tsdb:")) return emptyList()
        val hk = teamKeys(m.home, m.homeFull); val ak = teamKeys(m.away, m.awayFull)
        if (hk.isEmpty() && ak.isEmpty()) return emptyList()
        // przed meczem: audycja w czasie rozpoczęcia; w trakcie / po: teraz
        val t = if (m.state == State.PRE && m.start > 0) m.start else now
        val out = ArrayList<Broadcast>()
        for (ch in Repository.channels) {
            val p = Repository.programmes(ch).firstOrNull { it.start <= t + 30 * 60_000 && it.end > t } ?: continue
            val text = " " + fold(p.title + " " + p.subTitle + " " + p.category + " " + p.desc.take(400)) + " "
            val h = hk.any { hit(text, it) }; val a = ak.any { hit(text, it) }
            when {
                h && a -> out += Broadcast(ch, p, true)
                (h || a) && SPORT.containsMatchIn(text) -> out += Broadcast(ch, p, false)
            }
        }
        return out.sortedByDescending { it.sure }.take(6)
    }

    /** Kanał z meczem (najpewniejsze dopasowanie). */
    fun channelFor(m: Match, now: Long = System.currentTimeMillis()): Channel? = broadcasts(m, now).firstOrNull()?.channel
}
