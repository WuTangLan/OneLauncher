package com.marcus.onelauncher

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

data class WeatherNow(
    val temp: Int,
    val code: Int,
    val isDay: Boolean,
    val high: Int,
    val low: Int,
    val place: String,
    val fetchedAt: Long,
)

data class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

/**
 * Current weather from Open-Meteo (free, no API key). Location comes from the internet
 * connection (IP lookup) unless the user picked a city. Results are cached so the home
 * screen shows the last reading instantly, even offline.
 */
object WeatherRepository {

    private const val PREFS = "weather"
    private const val REFRESH_MS = 30 * 60 * 1000L        // fetch every 30 min
    private const val STALE_MS = 6 * 60 * 60 * 1000L      // hide readings older than 6 h
    private const val IP_LOOKUP_MS = 6 * 60 * 60 * 1000L  // re-check auto location every 6 h

    @Volatile private var inFlight = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- settings

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("enabled", on).apply()

    fun fahrenheit(ctx: Context) = prefs(ctx).getBoolean("fahrenheit", false)
    fun setFahrenheit(ctx: Context, f: Boolean) {
        prefs(ctx).edit().putBoolean("fahrenheit", f).remove("cache").remove("cache_at").remove("failed_at").apply()
    }

    /** True when the user picked a city, false for automatic location. */
    fun manualPlace(ctx: Context) = prefs(ctx).getBoolean("manual", false)
    fun placeName(ctx: Context): String? = prefs(ctx).getString("place", null)

    fun setPlace(ctx: Context, place: Place?) {
        val e = prefs(ctx).edit().remove("cache").remove("cache_at").remove("failed_at").remove("located_at")
        if (place == null) {
            e.putBoolean("manual", false)
        } else {
            e.putBoolean("manual", true)
                .putString("place", place.name)
                .putFloat("lat", place.lat.toFloat())
                .putFloat("lon", place.lon.toFloat())
        }
        e.apply()
    }

    // ---- reading

    fun cached(ctx: Context): WeatherNow? {
        val json = prefs(ctx).getString("cache", null) ?: return null
        return try {
            val o = JSONObject(json)
            WeatherNow(
                o.getInt("temp"), o.getInt("code"), o.getBoolean("day"),
                o.getInt("hi"), o.getInt("lo"), o.getString("place"), o.getLong("at"),
            ).takeIf { System.currentTimeMillis() - it.fetchedAt < STALE_MS }
        } catch (_: Exception) {
            null
        }
    }

    fun needsRefresh(ctx: Context): Boolean {
        if (!enabled(ctx) || inFlight) return false
        val at = prefs(ctx).getLong("cache_at", 0)
        val failedAt = prefs(ctx).getLong("failed_at", 0)
        val now = System.currentTimeMillis()
        // After a failure wait 5 minutes before trying again.
        return now - at > REFRESH_MS && now - failedAt > 5 * 60 * 1000L
    }

    /** Blocking network call; run on a background thread. */
    fun refresh(ctx: Context): WeatherNow? {
        if (inFlight) return cached(ctx)
        inFlight = true
        try {
            val p = prefs(ctx)
            if (!p.getBoolean("manual", false) &&
                System.currentTimeMillis() - p.getLong("located_at", 0) > IP_LOOKUP_MS
            ) {
                locateByIp()?.let { place ->
                    p.edit()
                        .putString("place", place.name)
                        .putFloat("lat", place.lat.toFloat())
                        .putFloat("lon", place.lon.toFloat())
                        .putLong("located_at", System.currentTimeMillis())
                        .apply()
                }
            }
            if (!p.contains("lat")) throw IllegalStateException("No location")
            val lat = p.getFloat("lat", 0f)
            val lon = p.getFloat("lon", 0f)
            val unit = if (fahrenheit(ctx)) "&temperature_unit=fahrenheit" else ""
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code,is_day" +
                "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto$unit"
            val o = JSONObject(httpGet(url))
            val cur = o.getJSONObject("current")
            val daily = o.getJSONObject("daily")
            val w = WeatherNow(
                temp = cur.getDouble("temperature_2m").roundToInt(),
                code = cur.getInt("weather_code"),
                isDay = cur.optInt("is_day", 1) == 1,
                high = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
                low = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
                place = p.getString("place", null) ?: "",
                fetchedAt = System.currentTimeMillis(),
            )
            val json = JSONObject()
                .put("temp", w.temp).put("code", w.code).put("day", w.isDay)
                .put("hi", w.high).put("lo", w.low).put("place", w.place).put("at", w.fetchedAt)
            p.edit().putString("cache", json.toString()).putLong("cache_at", w.fetchedAt).apply()
            return w
        } catch (_: Exception) {
            prefs(ctx).edit().putLong("failed_at", System.currentTimeMillis()).apply()
            return cached(ctx)
        } finally {
            inFlight = false
        }
    }

    /** City search for the "Choose a city" dialog. Blocking. */
    fun searchCity(query: String): List<Place> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val lang = Locale.getDefault().language.ifBlank { "en" }
        val o = JSONObject(httpGet("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=8&language=$lang&format=json"))
        val arr = o.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            val detail = listOf(r.optString("admin1"), r.optString("country"))
                .filter { it.isNotBlank() }.joinToString(", ")
            Place(r.getString("name"), detail, r.getDouble("latitude"), r.getDouble("longitude"))
        }
    }

    /** Approximate location from the TV's internet connection. Two free services, no key. */
    private fun locateByIp(): Place? {
        try {
            val o = JSONObject(httpGet("https://ipwho.is/"))
            if (o.optBoolean("success", false)) {
                return Place(o.optString("city"), o.optString("country"), o.getDouble("latitude"), o.getDouble("longitude"))
            }
        } catch (_: Exception) {
        }
        return try {
            val o = JSONObject(httpGet("https://get.geojs.io/v1/ip/geo.json"))
            // geojs returns coordinates as strings; optDouble parses both.
            val lat = o.optDouble("latitude"); val lon = o.optDouble("longitude")
            if (lat.isNaN() || lon.isNaN()) null else Place(o.optString("city"), o.optString("country"), lat, lon)
        } catch (_: Exception) {
            null
        }
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "OneLauncher/1.0 (Android TV)")
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    // ---- presentation

    /** Short condition name for a WMO weather code. */
    fun condition(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71, 73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80, 81 -> "Showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 97, 99 -> "Storms"
        else -> "—"
    }

    fun icon(code: Int, isDay: Boolean): Int = when (code) {
        0, 1 -> if (isDay) R.drawable.wx_clear_day else R.drawable.wx_clear_night
        2 -> if (isDay) R.drawable.wx_partly_day else R.drawable.wx_partly_night
        3 -> R.drawable.wx_cloudy
        45, 48 -> R.drawable.wx_fog
        51, 53, 55, 56, 57 -> R.drawable.wx_drizzle
        61, 63, 65, 66, 67, 80, 81, 82 -> R.drawable.wx_rain
        71, 73, 75, 77, 85, 86 -> R.drawable.wx_snow
        95, 96, 97, 99 -> R.drawable.wx_thunder
        else -> R.drawable.wx_cloudy
    }
}
