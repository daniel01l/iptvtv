package pl.iptv.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject

/** Pogoda z Open-Meteo (bez klucza API). */
object Weather {
    var geoBase = "https://geocoding-api.open-meteo.com/v1/search"
    var forecastBase = "https://api.open-meteo.com/v1/forecast"
    private const val TTL = 30 * 60_000L

    private fun get(url: String): JSONObject = Repository.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) error("HTTP ${r.code}")
        JSONObject(r.body!!.string())
    }

    /** Wyszukuje miejscowość i zapisuje współrzędne. Zwraca pełną nazwę. */
    suspend fun setCity(name: String): String = withContext(Dispatchers.IO) {
        val url = geoBase.toHttpUrl().newBuilder().addQueryParameter("name", name.trim()).addQueryParameter("count", "1")
            .addQueryParameter("language", "pl").build().toString()
        val r = get(url).optJSONArray("results")?.optJSONObject(0) ?: error("Nie znaleziono miejscowości „$name”")
        val full = r.getString("name") + (r.optString("admin1").takeIf { it.isNotBlank() }?.let { ", $it" } ?: "")
        Prefs.weatherCity = full; Prefs.weatherLat = r.getDouble("latitude").toString(); Prefs.weatherLon = r.getDouble("longitude").toString()
        Prefs.weatherAt = 0; Prefs.weatherText = ""
        full
    }

    val stale get() = System.currentTimeMillis() - Prefs.weatherAt > TTL

    /** Bieżąca pogoda, np. "☀ 12°C"; pamięć 30 min. */
    suspend fun refresh(force: Boolean = false): String = withContext(Dispatchers.IO) {
        if (Prefs.weatherLat.isBlank()) return@withContext ""
        if (!force && !stale && Prefs.weatherText.isNotBlank()) return@withContext Prefs.weatherText
        val url = forecastBase.toHttpUrl().newBuilder().addQueryParameter("latitude", Prefs.weatherLat)
            .addQueryParameter("longitude", Prefs.weatherLon).addQueryParameter("current", "temperature_2m,weather_code,is_day")
            .addQueryParameter("timezone", "auto").build().toString()
        val c = get(url).getJSONObject("current")
        val t = Math.round(c.getDouble("temperature_2m")).toInt()
        val text = "${icon(c.optInt("weather_code"), c.optInt("is_day", 1) == 1)} $t°C"
        Prefs.weatherText = text; Prefs.weatherAt = System.currentTimeMillis()
        text
    }

    fun icon(code: Int, day: Boolean = true) = when (code) {
        0 -> if (day) "☀" else "🌙"
        1, 2 -> if (day) "⛅" else "☁"
        3 -> "☁"
        45, 48 -> "🌫"
        in 51..67, in 80..82 -> "🌧"
        in 71..77, 85, 86 -> "❄"
        in 95..99 -> "⛈"
        else -> "🌡"
    }
}
