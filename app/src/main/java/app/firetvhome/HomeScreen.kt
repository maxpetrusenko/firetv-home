package app.firetvhome

import android.content.Context
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextClock
import java.util.Date

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
    private val bdayCard = Card(ctx, "Birthdays")
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
            textSize = 64f; setTextColor(Ui.TEXT); includeFontPadding = false
        }
        ampm = TextClock(ctx).apply {
            format12Hour = "a"; format24Hour = ""
            textSize = 22f; setTextColor(Ui.DIM); includeFontPadding = false
        }
        date = TextClock(ctx).apply {
            format12Hour = "EEEE, MMMM d"; format24Hour = "EEEE, MMMM d"
            textSize = 26f; setTextColor(Ui.DIM); includeFontPadding = false
        }
        clockRow.addView(clock)
        clockRow.addView(ampm, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 10 * d; bottomMargin = 8 * d })
        clockRow.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        clockRow.addView(date, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = 12 * d })
        root.addView(clockRow, LinearLayout.LayoutParams(-1, -2))

        // Cards: calendar on the left (~60%), birthdays over reminders on the right.
        calCard.view.visibility = View.GONE
        remCard.view.visibility = View.GONE
        bdayCard.view.visibility = View.GONE
        val cards = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        cards.addView(calCard.view, LinearLayout.LayoutParams(0, -1, 6f))
        val right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        right.addView(bdayCard.view, LinearLayout.LayoutParams(-1, -2))
        right.addView(remCard.view, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 12 * d })
        cards.addView(right, LinearLayout.LayoutParams(0, -1, 4f).apply { leftMargin = 16 * d })
        root.addView(cards, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 12 * d; bottomMargin = 12 * d })

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

    /**
     * [events] null means not loaded yet; [failed] says the last load failed. Events are grouped by day (today through
     * [days] days ahead), all-day first, titles wrapped to 2 lines.
     */
    fun renderCalendar(show: Boolean, events: List<Ev>?, failed: Boolean, showLabels: Boolean, now: Long, days: Int = Config.DEFAULT_CAL_DAYS) {
        Ui.gone(calCard.view, !show)
        if (!show) return
        val d = Ui.dp(ctx, 1)
        val b = calCard.body
        b.removeAllViews()
        val groups = events?.let { CalendarLogic.groupByDay(it, now, days, java.util.TimeZone.getDefault()) } ?: emptyList()
        if (events == null) b.addView(Ui.text(ctx, if (failed) "Calendar unavailable" else "Loading...", 20f, Ui.DIM))
        else if (groups.isEmpty()) b.addView(Ui.text(ctx, "No upcoming events", 20f, Ui.DIM))
        else for ((gi, g) in groups.withIndex()) {
            b.addView(Ui.text(ctx, g.label, 15f, Ui.ACCENT, bold = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = (if (gi == 0) 0 else 8) * d })
            for (e in g.events) {
                val time = if (e.allDay) "All day" else DateFormat.getTimeFormat(ctx).format(Date(e.start))
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(Ui.text(ctx, time, 15f, Ui.DIM), LinearLayout.LayoutParams(Ui.dp(ctx, 84), -2))
                row.addView(FitTextView(ctx, 18f, 13f).apply { text = e.title; setTextColor(Ui.TEXT) }, LinearLayout.LayoutParams(0, -2, 1f))
                if (showLabels && e.source.isNotEmpty())
                    row.addView(Ui.text(ctx, e.source, 13f, Ui.DIM), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 8 * d })
                b.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4 * d })
            }
        }
        trimToFit(calCard, dayList = true)
    }

    /** [items] null means not loaded yet. Shows up to 5 birthdays within [days] days, soonest first. */
    fun renderBirthdays(show: Boolean, items: List<Birthdays.Item>?, failed: Boolean, days: Int, now: Long) {
        Ui.gone(bdayCard.view, !show)
        if (!show) return
        val d = Ui.dp(ctx, 1)
        val b = bdayCard.body
        b.removeAllViews()
        if (items == null) b.addView(Ui.text(ctx, if (failed) "Birthdays unavailable" else "Loading...", 17f, Ui.DIM))
        else {
            val tz = java.util.TimeZone.getDefault()
            val w = Birthdays.window(items, now, days, tz)
            if (w.isEmpty()) b.addView(Ui.text(ctx, "No birthdays in the next $days days", 17f, Ui.DIM))
            else for (i in w.take(5)) b.addView(FitTextView(ctx, 17f, 13f).apply { text = Birthdays.line(i, now, tz); setTextColor(Ui.TEXT) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = 3 * d })
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
                for (it in shown) b.addView(Ui.text(ctx, "• $it", 18f))
                budget -= shown.size
                if (l.items.size > shown.size) b.addView(Ui.text(ctx, "+${l.items.size - shown.size} more", 15f, Ui.DIM))
            }
            if (b.childCount == 0) b.addView(Ui.text(ctx, "All done", 20f, Ui.DIM))
        }
        trimToFit(remCard)
    }

    /** After layout, drop trailing rows that run past the card, and (day lists) a day header left without events. */
    private fun trimToFit(c: Card, dayList: Boolean = false) {
        val vto = c.view.viewTreeObserver
        vto.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val o = if (c.view.viewTreeObserver.isAlive) c.view.viewTreeObserver else vto
                if (o.isAlive) o.removeOnGlobalLayoutListener(this)
                if (c.view.height <= 0) return
                val b = c.body
                val avail = c.view.height - c.view.paddingBottom
                var changed = false
                while (b.childCount > 1 && b.top + b.getChildAt(b.childCount - 1).bottom > avail) { b.removeViewAt(b.childCount - 1); changed = true }
                if (changed && dayList && b.childCount > 0 && b.getChildAt(b.childCount - 1) is android.widget.TextView) b.removeViewAt(b.childCount - 1)
            }
        })
    }
}
