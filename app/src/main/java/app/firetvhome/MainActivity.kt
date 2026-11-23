package app.firetvhome

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
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
    private var bdays: List<Birthdays.Item>? = null
    private var bdayErr = false
    private val queued = HashSet<String>() // blocks pushed while a reload was running

    private val tick = object : Runnable {
        override fun run() { refresh(); Ui.main.postDelayed(this, 15 * 60_000L) } // safety net; pushes and onResume do the real work
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        cfg = Config.load(this)
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
        Push.setListener(this)
        Ui.main.removeCallbacks(tick)
        Ui.main.post(tick)
        if (currentFocus == null) appsRow.getChildAt(0)?.requestFocus()
        if (firstFocus) { firstFocus = false; Ui.main.post { appsRow.getChildAt(0)?.requestFocus() } }
    }

    override fun onPause() {
        super.onPause()
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
        render()
        val doCal = Push.CALENDAR in blocks
        val doRem = Push.REMINDERS in blocks
        val doBday = Push.BIRTHDAYS in blocks
        Push.clearDirty(applicationContext, blocks) // trips has no card yet; clearing it is the whole reload
        if (!(doCal && cfg.hasCalendar) && !(doRem && cfg.hasTodos) && !(doBday && cfg.birthdays != null)) return
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
                    // Pushed file first when fresh (under 2 h); ICS/HA stays the fallback. The filter runs after the merge.
                    e = CalendarLogic.filter(Push.freshCalendar(app, System.currentTimeMillis()) ?: Data.calendar(c), c.exclude, c.includeExceptions)
                } catch (x: Exception) { ce = true; android.util.Log.w("firetv-home", "calendar: ${x.javaClass.simpleName}") }
            }
            var bl: List<Birthdays.Item>? = null
            var be = false
            if (doBday && c.birthdays != null) try { bl = Birthdays.load(app, c, System.currentTimeMillis()) } catch (x: Exception) { be = true; android.util.Log.w("firetv-home", "birthdays: ${x.javaClass.simpleName}") }
            Ui.main.post {
                if (doRem && c.hasTodos) { if (t != null) todos = t; todoErr = te }
                if (doCal && c.hasCalendar) { if (e != null) events = e; calErr = ce }
                if (doBday && c.birthdays != null) { if (bl != null) bdays = bl; bdayErr = be }
                busy.set(false)
                render()
                if (ts > 0) android.util.Log.i(Push.TAG, "render block=${blocks.joinToString(",")} ts=$ts now=${System.currentTimeMillis()}")
                if (queued.isNotEmpty()) { val q = HashSet(queued); queued.clear(); refresh(q) }
            }
        }
    }

    private fun render() {
        val now = System.currentTimeMillis()
        screen.renderCalendar(cfg.hasCalendar, events, calErr, cfg.showCalLabels, now, cfg.calDays, cfg.stripBookingCodes)
        screen.renderBirthdays(cfg.birthdays != null, bdays, bdayErr, cfg.birthdays?.days ?: Config.DEFAULT_BDAY_DAYS, now)
        screen.renderReminders(cfg.hasTodos, todos, todoErr, cfg.stripBookingCodes)
    }
}
