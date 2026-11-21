package app.firetvhome

import android.content.Context
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The home screen's view tree and the code that fills it. It holds no data and does no I/O: MainActivity feeds it,
 * and the JVM snapshot test feeds it demo data.
 */
class HomeScreen(private val ctx: Context, background: String) {
    private class Card(a: Context, title: String) {
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val view = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.cardBg(a)
            setPadding(Ui.dp(a, 20), Ui.dp(a, 16), Ui.dp(a, 20), Ui.dp(a, 16))
            addView(Ui.text(a, title.uppercase(), 13f, Ui.ACCENT, bold = true))
            addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(a, 8) })
        }
    }

    private val calCard = Card(ctx, "Calendar")
    private val remCard = Card(ctx, "Reminders")
    private val notCard = Card(ctx, "Notifications")
    val appsRow = LinearLayout(ctx)
    val appsScroll = HorizontalScrollView(ctx)
    val view: View
    lateinit var clock: TextClock; private set
    lateinit var ampm: TextClock; private set
    lateinit var date: TextClock; private set

    init {
        val d = Ui.dp(ctx, 1)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 48), Ui.dp(ctx, 28), Ui.dp(ctx, 48), Ui.dp(ctx, 24))
        }

        // Clock + date
        val clockRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        clock = TextClock(ctx).apply {
            format12Hour = "h:mm"; format24Hour = "HH:mm"
            textSize = 84f; setTextColor(Ui.TEXT); includeFontPadding = false
        }
        ampm = TextClock(ctx).apply {
            format12Hour = "a"; format24Hour = ""
            textSize = 26f; setTextColor(Ui.DIM); includeFontPadding = false
        }
        date = TextClock(ctx).apply {
            format12Hour = "EEEE, MMMM d"; format24Hour = "EEEE, MMMM d"
            textSize = 30f; setTextColor(Ui.DIM); includeFontPadding = false
        }
        clockRow.addView(clock)
        clockRow.addView(ampm, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 10 * d; bottomMargin = 8 * d })
        clockRow.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        clockRow.addView(date, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = 12 * d })
        root.addView(clockRow, LinearLayout.LayoutParams(-1, -2))

        // Cards
        val cards = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        fun add(c: Card, w: Float, first: Boolean = false) =
            cards.addView(c.view, LinearLayout.LayoutParams(0, -1, w).apply { if (!first) leftMargin = 16 * d })
        add(calCard, 1f, true); add(remCard, 1f); add(notCard, 1.2f)
        calCard.view.visibility = View.GONE
        remCard.view.visibility = View.GONE
        notCard.view.visibility = View.GONE
        root.addView(cards, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 20 * d; bottomMargin = 20 * d })

        // Apps row
        appsRow.apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 3 * d, 0, 3 * d) }
        appsScroll.apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(appsRow)
        }
        root.addView(appsScroll, LinearLayout.LayoutParams(-1, -2))
        val frame = Ui.withBackground(ctx, root, background) as FrameLayout
        if (background == Config.BG_BALI) {
            frame.addView(Ui.text(ctx, "Photo: Jorge Franganillo, CC BY 2.0", 12f, 0x99FFFFFF.toInt()).apply { setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt()) },
                FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = Ui.dp(ctx, 16); bottomMargin = Ui.dp(ctx, 6) })
        }
        view = frame
    }

    /** [events] null means not loaded yet; [failed] says the last load failed. */
    fun renderCalendar(show: Boolean, events: List<Ev>?, failed: Boolean, showLabels: Boolean, now: Long) {
        Ui.gone(calCard.view, !show)
        if (!show) return
        val b = calCard.body
        b.removeAllViews()
        val today = Data.dayStart(now)
        val ev = events?.filter { it.allDay || it.end > now || it.start >= now }
            ?.sortedWith(compareBy({ Data.dayStart(maxOf(it.start, today)) }, { !it.allDay }, { it.start }))
            ?: emptyList()
        if (events == null) b.addView(Ui.text(ctx, if (failed) "Calendar unavailable" else "Loading...", 20f, Ui.DIM))
        else if (ev.isEmpty()) b.addView(Ui.text(ctx, "No events this week", 20f, Ui.DIM))
        else for (e in ev.take(6)) {
            val day = Math.round((Data.dayStart(maxOf(e.start, today)) - today) / 86_400_000.0).toInt()
            val label = when (day) { 0 -> "Today"; 1 -> "Tomorrow"; else -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(e.start)) }
            val time = if (e.allDay) "All day" else DateFormat.getTimeFormat(ctx).format(Date(e.start))
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Ui.text(ctx, label, 15f, Ui.ACCENT, bold = true), LinearLayout.LayoutParams(Ui.dp(ctx, 90), -2))
            row.addView(Ui.text(ctx, time, 15f, Ui.DIM), LinearLayout.LayoutParams(Ui.dp(ctx, 64), -2))
            row.addView(Ui.text(ctx, e.title, 18f), LinearLayout.LayoutParams(0, -2, 1f))
            if (showLabels && e.source.isNotEmpty())
                row.addView(Ui.text(ctx, e.source, 13f, Ui.DIM), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.dp(ctx, 8) })
            b.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(ctx, 6) })
        }
    }

    fun renderReminders(show: Boolean, lists: List<TodoList>?, failed: Boolean) {
        Ui.gone(remCard.view, !show)
        if (!show) return
        val b = remCard.body
        b.removeAllViews()
        if (lists == null) b.addView(Ui.text(ctx, if (failed) "Reminders unavailable" else "Loading...", 20f, Ui.DIM))
        else {
            var budget = 6
            for (l in lists) {
                if (l.items.isEmpty() || budget <= 0) continue
                b.addView(Ui.text(ctx, l.label, 15f, Ui.DIM, bold = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(ctx, 6) })
                val shown = l.items.take(maxOf(1, minOf(3, budget)))
                for (it in shown) b.addView(Ui.text(ctx, "• $it", 20f))
                budget -= shown.size
                if (l.items.size > shown.size) b.addView(Ui.text(ctx, "+${l.items.size - shown.size} more", 15f, Ui.DIM))
            }
            if (b.childCount == 0) b.addView(Ui.text(ctx, "All done", 20f, Ui.DIM))
        }
    }

    /** [items] null hides the card (notification access not granted). */
    fun renderNotifs(items: List<NotifService.Item>?) {
        Ui.gone(notCard.view, items == null)
        if (items == null) return
        val b = notCard.body
        b.removeAllViews()
        if (items.isEmpty()) b.addView(Ui.text(ctx, "No recent notifications", 20f, Ui.DIM))
        for (n in items) {
            b.addView(Ui.text(ctx, n.app, 13f, Ui.DIM, bold = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(ctx, 6) })
            val head = n.title.ifEmpty { n.text }
            b.addView(Ui.text(ctx, head, 19f, bold = true))
            if (n.title.isNotEmpty() && n.text.isNotEmpty()) b.addView(Ui.text(ctx, n.text, 17f, Ui.DIM))
        }
    }
}
