package pl.iptv.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class RecJob(
    val id: String,
    val channelKey: String,
    val channelName: String,
    val url: String,
    val title: String,
    val start: Long,
    /** Long.MAX_VALUE = do ręcznego zatrzymania */
    val end: Long,
    var state: String = SCHEDULED,
    var file: String = "",
    var error: String = "",
    val userAgent: String = "",
    val referrer: String = "",
) {
    companion object { const val SCHEDULED = "scheduled"; const val RECORDING = "recording"; const val DONE = "done"; const val FAILED = "failed" }
}

object Recordings {
    private var file: File? = null
    private val jobs = ArrayList<RecJob>()

    @Synchronized fun init(f: File) {
        file = f; jobs.clear()
        runCatching {
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) a.getJSONObject(i).let { j ->
                jobs += RecJob(j.getString("id"), j.getString("ch"), j.getString("chName"), j.getString("url"), j.getString("title"),
                    j.getLong("start"), j.getLong("end"), j.getString("state"), j.optString("file"), j.optString("error"),
                    j.optString("ua"), j.optString("ref"))
            }
        }
        // przerwane przez wyłączenie urządzenia
        jobs.filter { it.state == RecJob.RECORDING }.forEach { it.state = if (File(it.file).length() > 0) RecJob.DONE else RecJob.FAILED }
    }

    @Synchronized private fun save() {
        val a = JSONArray()
        jobs.forEach { j -> a.put(JSONObject().put("id", j.id).put("ch", j.channelKey).put("chName", j.channelName).put("url", j.url)
            .put("title", j.title).put("start", j.start).put("end", j.end).put("state", j.state).put("file", j.file)
            .put("error", j.error).put("ua", j.userAgent).put("ref", j.referrer)) }
        runCatching { file?.writeText(a.toString()) }
    }

    @Synchronized fun all(): List<RecJob> = jobs.sortedByDescending { it.start }
    @Synchronized fun get(id: String) = jobs.firstOrNull { it.id == id }
    @Synchronized fun add(j: RecJob) { jobs += j; save() }
    @Synchronized fun update(j: RecJob) { save() }
    @Synchronized fun remove(id: String) { jobs.removeAll { it.id == id }; save() }

    /** Zaplanowane/trwające nagranie tego programu (kanał + czas). */
    @Synchronized fun forProgramme(ch: Channel, p: Programme) =
        jobs.firstOrNull { it.channelKey == ch.key && it.state in listOf(RecJob.SCHEDULED, RecJob.RECORDING) && it.start < p.end && it.end > p.start }

    @Synchronized fun activeFor(chKey: String) = jobs.firstOrNull { it.channelKey == chKey && it.state == RecJob.RECORDING }
}
