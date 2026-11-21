package app.firetvhome

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity(), Push.Listener {
    private lateinit var cfg: Config
    private lateinit var screen: HomeScreen
    private lateinit var appsRow: LinearLayout
    private lateinit var appsScroll: HorizontalScrollView
    private var appsKey = ""
    private var firstFocus = true
    private val busy = AtomicBoolean(false)

    private var todos: List<TodoList>? = null
    private var todoErr = false
    private var events: List<Ev>? = null
    private var calErr = false
    private val queued = HashSet<String>() // blocks pushed while a reload was running

    private val tick = object : Runnable {
        override fun run() { refresh(); Ui.main.postDelayed(this, 15 * 60_000L) } // safety net; pushes and onResume do the real work
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        cfg = Config.load(this)
        try { android.service.notification.NotificationListenerService.requestRebind(android.content.ComponentName(this, NotifService::class.java)) } catch (e: Exception) { }
        screen = HomeScreen(this, cfg.background)
        appsRow = screen.appsRow
        appsScroll = screen.appsScroll
        val frame = screen.view
        setContentView(frame)
        buildApps()
    }

    private fun buildApps() {
        val key = cfg.appOrder.joinToString(",")
        val installed = cfg.appOrder.filter { packageManager.getLaunchIntentForPackage(it) != null || packageManager.getLeanbackLaunchIntentForPackage(it) != null }
        val k = key + "|" + installed.joinToString(",")
        if (k == appsKey) return
        appsKey = k
        appsRow.removeAllViews()
        val pm = packageManager
        for (pkg in installed) {
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
            appsRow.addView(Ui.tile(this, label, 98, { Ui.launchPackage(this, pkg) }, loader = { pm.getApplicationIcon(pkg) }))
        }
        appsRow.addView(Ui.tile(this, "All apps", 98, { startActivity(Intent(this, AllAppsActivity::class.java)) }, iconRes = R.drawable.ic_apps))
        appsRow.addView(Ui.tile(this, "Settings", 98, { Ui.settings(this) }, iconRes = R.drawable.ic_settings))
        HomeSwitch.tile(this)?.let { appsRow.addView(it) }
        appsRow.getChildAt(0).requestFocus()
    }

    override fun onResume() {
        super.onResume()
        HomeSwitch.onResume(this)
        NotifService.onChange = { Ui.main.post { renderNotifs() } }
        Push.setListener(this)
        Ui.main.removeCallbacks(tick)
        Ui.main.post(tick)
        if (currentFocus == null) appsRow.getChildAt(0)?.requestFocus()
        if (firstFocus) { firstFocus = false; Ui.main.post { appsRow.getChildAt(0)?.requestFocus() } }
    }

    override fun onPause() {
        super.onPause()
        NotifService.onChange = null
        Push.setListener(null)
        Ui.main.removeCallbacks(tick)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Home pressed while already on Home: reset scroll and focus.
        appsScroll.scrollTo(0, 0)
        appsRow.getChildAt(0)?.requestFocus()
        Ui.main.removeCallbacks(tick)
        Ui.main.post(tick)
    }

    override fun onBackPressed() { /* this is home: stay put */ }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { Ui.settings(this); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPush(blocks: Set<String>, ts: Long) = refresh(blocks, ts)

    private fun refresh(blocks: Set<String> = Push.ALL, ts: Long = 0L) {
        cfg = Config.load(this)
        buildApps()
        renderNotifs()
        render()
        val doCal = Push.CALENDAR in blocks
        val doRem = Push.REMINDERS in blocks
        Push.clearDirty(applicationContext, blocks) // trips has no card yet; clearing it is the whole reload
        if (!(doCal && cfg.hasCalendar) && !(doRem && cfg.hasTodos)) return
        if (!busy.compareAndSet(false, true)) { queued.addAll(blocks); return }
        val c = cfg
        val app = applicationContext
        Ui.io.execute {
            var t: List<TodoList>? = null
            var te = false
            var e: List<Ev>? = null
            var ce = false
            if (doRem && c.hasTodos) try { t = Data.todos(c) } catch (x: Exception) { te = true; android.util.Log.w("firetv-home", "todos: ${x.javaClass.simpleName}") }
            if (doCal && c.hasCalendar) {
                try {
                    // Pushed file first when fresh (under 2 h); ICS/HA stays the fallback.
                    e = Push.freshCalendar(app, System.currentTimeMillis()) ?: Data.calendar(c)
                    Alerts.save(app, e)
                } catch (x: Exception) { ce = true; android.util.Log.w("firetv-home", "calendar: ${x.javaClass.simpleName}") }
                try { Alerts.check(app) } catch (x: Exception) { android.util.Log.w("firetv-home", "alerts: ${x.javaClass.simpleName}") }
            }
            Ui.main.post {
                if (doRem && c.hasTodos) { if (t != null) todos = t; todoErr = te }
                if (doCal && c.hasCalendar) { if (e != null) events = e; calErr = ce }
                busy.set(false)
                render()
                if (ts > 0) android.util.Log.i(Push.TAG, "render block=${blocks.joinToString(",")} ts=$ts now=${System.currentTimeMillis()}")
                if (queued.isNotEmpty()) { val q = HashSet(queued); queued.clear(); refresh(q) }
            }
        }
    }

    private fun render() {
        screen.renderCalendar(cfg.hasCalendar, events, calErr, cfg.showCalLabels, System.currentTimeMillis())
        screen.renderReminders(cfg.hasTodos, todos, todoErr)
    }

    private fun renderNotifs() = screen.renderNotifs(if (NotifService.connected) NotifService.recent(3) else null)
}
