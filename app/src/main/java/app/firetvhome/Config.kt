package app.firetvhome

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A calendar provider. Every source is fetched, the results are merged and sorted. */
sealed class CalSource(val label: String) {
    class IcsFeed(val url: String, label: String) : CalSource(label)
    class HaCalendar(val entity: String, label: String) : CalSource(label)
}

/** A reminders provider. Each yields one or more named lists. */
sealed class RemSource {
    class HaTodo(val entities: List<Pair<String, String>>) : RemSource()
    /** JSON written by the companion helper, read from [url] (http) or [file] (absolute path). One is non-empty. */
    class AppleReminders(val url: String, val file: String) : RemSource()
}

/** Local config read from <external files dir>/config.json (see config.example.json). */
class Config(
    val haUrl: String,
    val haToken: String,
    val calendars: List<CalSource>,
    val reminders: List<RemSource>,
    val appOrder: List<String>,
    val alertMinutes: Int,
    val background: String,
) {
    val haReady get() = haUrl.isNotEmpty() && haToken.isNotEmpty()
    val hasCalendar get() = calendars.isNotEmpty()
    val hasTodos get() = reminders.isNotEmpty()
    /** Source labels are shown in the week view only when there is more than one source. */
    val showCalLabels get() = calendars.size > 1

    companion object {
        const val BG_DEFAULT = "default"
        const val BG_BALI = "bali"

        val DEFAULT_APPS = listOf(
            "org.smarttube.stable", "com.netflix.ninja", "com.amazon.firetv.youtube",
            "org.videolan.vlc", "com.hbo.hbonow", "com.phlox.tvwebbrowser",
        )

        fun load(ctx: Context): Config {
            val files = listOfNotNull(ctx.getExternalFilesDir(null), ctx.filesDir)
            val f = files.map { File(it, "config.json") }.firstOrNull { it.canRead() }
            return parse(if (f != null) try { f.readText() } catch (e: Exception) { "" } else "", f?.parentFile)
        }

        /** Pure parser (JVM-testable). Bad or empty JSON gives an empty config. [baseDir] resolves relative reminder `file` paths. */
        fun parse(text: String, baseDir: File? = null): Config {
            val j = try { if (text.isBlank()) JSONObject() else JSONObject(text) } catch (e: Exception) { JSONObject() }
            val haUrl = j.optString("ha_url").trim().trimEnd('/')
            val haToken = j.optString("ha_token").trim()
            val haReady = haUrl.isNotEmpty() && haToken.isNotEmpty()

            // Calendars: the "calendars" list, or the old flat keys (HA entity wins over ics_url, as before).
            val cals = ArrayList<CalSource>()
            j.optJSONArray("calendars")?.let { a ->
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    when (o.optString("type").lowercase()) {
                        "ics" -> o.optString("url").trim().takeIf { it.isNotEmpty() }
                            ?.let { cals.add(CalSource.IcsFeed(it, o.optString("label").trim())) }
                        "ha_calendar" -> o.optString("entity").trim().takeIf { it.isNotEmpty() && haReady }
                            ?.let { cals.add(CalSource.HaCalendar(it, o.optString("label").trim())) }
                    }
                }
            }
            if (j.optJSONArray("calendars") == null) {
                val ent = j.optString("calendar_entity").trim()
                val ics = j.optString("ics_url").trim()
                if (haReady && ent.isNotEmpty()) cals.add(CalSource.HaCalendar(ent, ""))
                else if (ics.isNotEmpty()) cals.add(CalSource.IcsFeed(ics, ""))
            }

            // Reminders: the "reminders" list, or the old todo_entities key.
            val rems = ArrayList<RemSource>()
            val remArr = j.optJSONArray("reminders")
            if (remArr != null) {
                for (i in 0 until remArr.length()) {
                    val o = remArr.optJSONObject(i) ?: continue
                    when (o.optString("type").lowercase()) {
                        "ha_todo" -> {
                            val ents = todoEntities(o.optJSONArray("entities"))
                            if (haReady && ents.isNotEmpty()) rems.add(RemSource.HaTodo(ents))
                        }
                        "apple_reminders" -> {
                            val url = o.optString("url").trim()
                            val file = o.optString("file").trim()
                            if (url.isNotEmpty()) rems.add(RemSource.AppleReminders(url, ""))
                            else if (file.isNotEmpty()) {
                                val resolved = if (File(file).isAbsolute || baseDir == null) file else File(baseDir, file).path
                                rems.add(RemSource.AppleReminders("", resolved))
                            }
                        }
                    }
                }
            } else {
                val ents = todoEntities(j.optJSONArray("todo_entities"))
                if (haReady && ents.isNotEmpty()) rems.add(RemSource.HaTodo(ents))
            }

            val apps = ArrayList<String>()
            j.optJSONArray("app_order")?.let { a -> for (i in 0 until a.length()) apps.add(a.optString(i)) }
            val bg = (j.optString("background").ifEmpty { j.optString("theme") }).trim().lowercase()
            return Config(
                haUrl, haToken, cals, rems,
                if (apps.isEmpty()) DEFAULT_APPS else apps,
                j.optInt("calendar_alert_minutes", 10).coerceAtLeast(0),
                if (bg == BG_BALI) BG_BALI else BG_DEFAULT,
            )
        }

        private fun todoEntities(a: JSONArray?): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            if (a == null) return out
            for (i in 0 until a.length()) {
                val o = a.opt(i)
                if (o is JSONObject) {
                    val id = o.optString("entity")
                    if (id.isNotEmpty()) out.add(id to o.optString("label", pretty(id)).ifEmpty { pretty(id) })
                } else if (o is String && o.isNotEmpty()) out.add(o to pretty(o))
            }
            return out
        }

        private fun pretty(id: String) =
            id.substringAfter('.').replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
