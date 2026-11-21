package app.firetvhome

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import java.io.File
import java.util.Date

/**
 * Pre-event notices. One "next alert" alarm at a time: the latest event list is cached in filesDir,
 * and check() shows anything now due, then arms an alarm for the earliest remaining one. check() runs
 * after every refresh, from the alarm receiver and after boot. Alerted instances are remembered in prefs.
 */
object Alerts {
    const val ACTION_ALERT = "app.firetvhome.ALERT"
    const val ACTION_DEBUG = "app.firetvhome.DEBUG_ALERT"
    private const val PREFS = "alerts"
    private const val KEY = "alerted"
    private const val CACHE = "events.cache"
    private const val CHANNEL = "event_alerts"
    private const val DAY = 86_400_000L

    @Synchronized
    fun save(ctx: Context, events: List<Ev>) {
        val sb = StringBuilder()
        for (e in events) {
            sb.append(e.start).append('\t').append(e.end).append('\t').append(if (e.allDay) 1 else 0).append('\t')
                .append(e.uid.replace(Regex("[\t\r\n]"), " ")).append('\t')
                .append(e.title.replace(Regex("[\t\r\n]"), " ")).append('\n')
        }
        val tmp = File(ctx.filesDir, "$CACHE.tmp")
        tmp.writeText(sb.toString())
        tmp.renameTo(File(ctx.filesDir, CACHE))
    }

    @Synchronized
    private fun load(ctx: Context): List<Ev> {
        val f = File(ctx.filesDir, CACHE)
        if (!f.canRead()) return emptyList()
        return f.readLines().mapNotNull { l ->
            val p = l.split('\t', limit = 5)
            if (p.size < 5) null
            else try { Ev(p[4], p[0].toLong(), p[1].toLong(), p[2] == "1", p[3]) } catch (e: Exception) { null }
        }
    }

    private fun pending(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, AlertReceiver::class.java).setAction(ACTION_ALERT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    @Synchronized
    fun check(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx)
        val mins = Config.load(ctx).alertMinutes
        if (mins <= 0) { am.cancel(pi); return }
        val now = System.currentTimeMillis()
        val lead = mins * 60_000L
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val alerted = HashSet<String>(prefs.getStringSet(KEY, emptySet()) ?: emptySet())
        alerted.removeAll { (it.substringAfterLast('@').toLongOrNull() ?: 0L) < now - DAY }
        var next = Long.MAX_VALUE
        var first = true
        for (e in load(ctx).filter { !it.allDay && it.start > now }.sortedBy { it.start }) {
            if (e.key in alerted) continue
            val fire = e.start - lead
            if (fire <= now) {
                alerted.add(e.key)
                show(ctx, e.title, e.start, now, overlay = first)
                first = false
            } else if (fire < next) next = fire
        }
        prefs.edit().putStringSet(KEY, alerted).apply()
        if (next == Long.MAX_VALUE) { am.cancel(pi); return }
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi) // exact alarms not permitted (API 31+)
        }
    }

    /** Overlay (if allowed) plus a regular notification. */
    fun show(ctx: Context, title: String, start: Long, now: Long, overlay: Boolean = true) {
        val mins = ((start - now + 30_000L) / 60_000L).coerceAtLeast(1)
        val head = "In $mins min"
        val at = DateFormat.getTimeFormat(ctx).format(Date(start))
        notify(ctx, head, title, at)
        if (overlay) AlertOverlayService.show(ctx, head, title, at)
    }

    @Suppress("DEPRECATION")
    private fun notify(ctx: Context, head: String, title: String, at: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val b = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Event alerts", NotificationManager.IMPORTANCE_HIGH))
            Notification.Builder(ctx, CHANNEL)
        } else Notification.Builder(ctx).setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL)
        b.setSmallIcon(R.drawable.ic_launcher).setContentTitle(title).setContentText("$head · $at")
            .setCategory(Notification.CATEGORY_REMINDER).setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        nm.notify((title + at).hashCode(), b.build())
    }
}

/**
 * Receives the alarm, boot, package-replaced and debug broadcasts. Exported only so that
 * `adb shell am broadcast -n app.firetvhome/.AlertReceiver -a app.firetvhome.DEBUG_ALERT` works;
 * the debug action can only show a message, never read data.
 */
class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Alerts.ACTION_DEBUG -> {
                val m = intent.getIntExtra("minutes", 10).coerceIn(0, 999)
                val now = System.currentTimeMillis()
                Alerts.show(ctx, (intent.getStringExtra("title") ?: "Test event").take(80), now + m * 60_000L, now)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Alerts.check(ctx) // from the cached list, no network
                val c = Config.load(ctx)
                if (c.hasCalendar) {
                    val p = goAsync()
                    Ui.io.execute {
                        try { Alerts.save(ctx, Data.calendar(c)); Alerts.check(ctx) } catch (e: Exception) { } finally { p.finish() }
                    }
                }
            }
            else -> Alerts.check(ctx)
        }
    }
}

/** Hosts the overlay card for 15 s, then stops itself. */
class AlertOverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var card: View? = null
    private val remove = Runnable { removeCard(); stopSelf() }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        removeCard()
        handler.removeCallbacks(remove)
        if (i == null || (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this))) { stopSelf(); return START_NOT_STICKY }
        try {
            val v = build(i.getStringExtra("head") ?: "", i.getStringExtra("title") ?: "", i.getStringExtra("at") ?: "")
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(
                -2, -2, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.END; x = Ui.dp(this@AlertOverlayService, 32); y = Ui.dp(this@AlertOverlayService, 32) }
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(v, lp)
            card = v
            handler.postDelayed(remove, 15_000L)
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun build(head: String, title: String, at: String): View {
        val c = this
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xEB141418.toInt()); cornerRadius = Ui.dp(c, 16).toFloat() }
            setPadding(Ui.dp(c, 22), Ui.dp(c, 16), Ui.dp(c, 22), Ui.dp(c, 16))
            addView(Ui.text(c, head, 18f, Ui.ACCENT, bold = true))
            addView(Ui.text(c, title, 24f, bold = true, lines = 2).apply { maxWidth = Ui.dp(c, 420) },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(c, 4) })
            addView(Ui.text(c, at, 18f, Ui.DIM))
        }
    }

    private fun removeCard() {
        card?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (e: Exception) { } }
        card = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(remove)
        removeCard()
    }

    companion object {
        fun show(ctx: Context, head: String, title: String, at: String) {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) return
            try {
                ctx.startService(Intent(ctx, AlertOverlayService::class.java).putExtra("head", head).putExtra("title", title).putExtra("at", at))
            } catch (e: RuntimeException) { } // background start refused on API 26+: the notification remains
        }
    }
}
