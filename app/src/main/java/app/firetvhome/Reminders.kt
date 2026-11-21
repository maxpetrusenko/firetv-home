package app.firetvhome

import org.json.JSONObject

/**
 * Parser for the reminders file written by the companion helper (companion/apple-reminders-helper).
 * Schema v1:
 *   {"updated":"2026-01-01T12:00:00Z","lists":[{"name":"Home","items":[{"title":"Buy milk","due":"2026-01-02T09:00:00Z"},{"title":"Call","due":null}]}]}
 * `updated` is informational. `due` is an ISO 8601 date or date-time, or null. Lists keep file order;
 * inside a list, items with a due date come first (earliest first), then the rest in file order.
 */
object Reminders {
    /** Null when [json] is not an object with a `lists` array. Lists without a name or items, and items without a title, are skipped. */
    fun parseApple(json: String): List<TodoList>? = try {
        val arr = JSONObject(json).optJSONArray("lists")
        if (arr == null) null
        else {
            val out = ArrayList<TodoList>()
            for (i in 0 until arr.length()) {
                val l = arr.optJSONObject(i) ?: continue
                val name = l.optString("name").trim()
                val items = l.optJSONArray("items") ?: continue
                if (name.isEmpty()) continue
                val rows = ArrayList<Pair<String, Long?>>()
                for (k in 0 until items.length()) {
                    val it = items.optJSONObject(k) ?: continue
                    val title = it.optString("title").trim()
                    if (title.isEmpty()) continue
                    val due = if (it.isNull("due")) null else Data.isoToMs(it.optString("due"))
                    rows.add(title to due)
                }
                val sorted = rows.filter { it.second != null }.sortedBy { it.second } + rows.filter { it.second == null }
                out.add(TodoList(name, sorted.map { it.first }))
            }
            out
        }
    } catch (e: Exception) { null }
}
