package app.firetvhome

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class TodoList(val label: String, val items: List<String>)
class Ev(val title: String, val start: Long, val end: Long, val allDay: Boolean, val uid: String = "", val source: String = "") {
    fun copy(source: String) = Ev(title, start, end, allDay, uid, source)
}

object Data {
    private const val TAG = "firetv-home"

    internal fun fetch(url: String): String = call(url)

    private fun call(url: String, method: String = "GET", token: String? = null, body: String? = null): String {
        if (url.startsWith("file:")) return java.io.File(java.net.URI(url)).readText()
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6000
            c.readTimeout = 10000
            c.requestMethod = method
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    // ---- Reminders: every configured provider, lists in config order ----
    fun todos(c: Config): List<TodoList> {
        val out = ArrayList<TodoList>()
        var ok = 0
        var last: Exception? = null
        for (src in c.reminders) {
            try {
                out.addAll(when (src) {
                    is RemSource.HaTodo -> haTodos(c, src)
                    is RemSource.AppleReminders -> appleReminders(src)
                })
                ok++
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "reminders source: ${e.javaClass.simpleName}")
            }
        }
        if (ok == 0 && last != null) throw last
        return out
    }

    private fun haTodos(c: Config, src: RemSource.HaTodo): List<TodoList> {
        val body = JSONObject()
            .put("entity_id", JSONArray(src.entities.map { it.first }))
            .put("status", "needs_action").toString()
        val r = JSONObject(call(c.haUrl + "/api/services/todo/get_items?return_response", "POST", c.haToken, body))
            .getJSONObject("service_response")
        return src.entities.map { (id, label) ->
            val arr = r.optJSONObject(id)?.optJSONArray("items")
            val items = ArrayList<String>()
            if (arr != null) for (i in 0 until arr.length()) items.add(arr.getJSONObject(i).optString("summary"))
            TodoList(label, items)
        }
    }

    private fun appleReminders(src: RemSource.AppleReminders): List<TodoList> {
        val text = if (src.url.isNotEmpty()) call(src.url) else java.io.File(src.file).readText()
        return Reminders.parseApple(text)
            ?: throw IOException("reminders file is not valid schema v1")
    }

    // ---- Calendar: today through the next c.calDays days (the filter runs after sources merge, in CalendarLogic) ----
    fun calendar(c: Config): List<Ev> {
        val from = dayStart(System.currentTimeMillis())
        val to = Calendar.getInstance().apply { timeInMillis = from; add(Calendar.DAY_OF_MONTH, c.calDays) }.timeInMillis
        val lists = ArrayList<List<Ev>>()
        var last: Exception? = null
        for (src in c.calendars) {
            try {
                val evs = when (src) {
                    is CalSource.IcsFeed -> Ics.parse(call(src.url), from, to)
                    is CalSource.HaCalendar -> haEvents(c, src.entity, from, to)
                }
                lists.add(evs.map { it.copy(source = src.label) })
            } catch (e: Exception) {
                last = e // one broken feed must not hide the others
                Log.w(TAG, "calendar source: ${e.javaClass.simpleName}")
            }
        }
        if (lists.isEmpty() && last != null) throw last
        return mergeEvents(lists)
    }

    /** Concatenate per-source event lists into one list sorted by start (stable, so equal starts keep source order). */
    fun mergeEvents(lists: List<List<Ev>>): List<Ev> = lists.flatten().sortedBy { it.start }

    internal fun haEvents(c: Config, entity: String, dayStart: Long, dayEnd: Long): List<Ev> {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val q = "start=" + URLEncoder.encode(f.format(dayStart), "UTF-8") +
            "&end=" + URLEncoder.encode(f.format(dayEnd), "UTF-8")
        val arr = JSONArray(call("${c.haUrl}/api/calendars/$entity?$q", token = c.haToken))
        val out = ArrayList<Ev>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val st = o.optJSONObject("start") ?: continue
            val en = o.optJSONObject("end")
            val allDay = st.has("date")
            val sMs = isoToMs(if (allDay) st.optString("date") else st.optString("dateTime")) ?: continue
            val eMs = en?.let { isoToMs(if (allDay) it.optString("date") else it.optString("dateTime")) } ?: sMs
            out.add(Ev(o.optString("summary", "(no title)"), sMs, eMs, allDay, o.optString("uid")))
        }
        return out
    }

    internal fun isoToMs(v: String): Long? {
        if (v.isEmpty()) return null
        return try {
            if (v.length == 10) SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(v)?.time
            else {
                val t = v.replace(Regex("\\.\\d+"), "")
                val hasZone = t.endsWith("Z") || Regex("[+-]\\d\\d:?\\d\\d$").containsMatchIn(t)
                SimpleDateFormat(if (hasZone) "yyyy-MM-dd'T'HH:mm:ssXXX" else "yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(t)?.time
            }
        } catch (e: Exception) {
            null // unparseable date: skip the value (no logging, so pure JVM tests do not touch android.util.Log)
        }
    }

    fun dayStart(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
