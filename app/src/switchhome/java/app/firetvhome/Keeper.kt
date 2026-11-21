package app.firetvhome

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.util.Log

/**
 * Keeps firetv-home on top while it holds the Home role. Polls UsageStats and moves our
 * task back to the front once dumbtv home has settled in front (or Amazon's home has stayed
 * there for a while). See [KeeperPolicy]; we never move tasks at the same moment as dumbtv.
 * The poll stops the service as soon as the Home role is released.
 * Needs `appops set app.firetvhome GET_USAGE_STATS allow`.
 */
class Keeper : Service() {
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var usage: UsageStatsManager? = null
    private val policy = KeeperPolicy()
    private var lastMoveAt = 0L
    private var loggedNoAccess = false
    private var everSawEvent = false

    private val poll = object : Runnable {
        override fun run() {
            try {
                if (!HomeRole.isActive(this@Keeper)) { stopSelf(); return }
                check()
            } catch (t: Throwable) {
                Log.w(TAG, "poll: $t")
            }
            worker?.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        usage = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        running = true
        startForeground(NOTIF_ID, notification())
        thread = HandlerThread("ftv-keeper").also { it.start() }
        worker = Handler(thread!!.looper).also { it.post(poll) }
    }

    @Suppress("DEPRECATION")
    private fun notification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Home keeper", NotificationManager.IMPORTANCE_MIN))
            Notification.Builder(this, CHANNEL)
        } else {
            Notification.Builder(this).setPriority(Notification.PRIORITY_MIN)
        }
        return b.setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("firetv-home")
            .setContentText("home keeper")
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!HomeRole.isActive(this)) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        // OPSTR_GET_USAGE_STATS is public only from API 29; the string works on 25.
        return ops.checkOpNoThrow("android:get_usage_stats", Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun check() {
        val um = usage ?: return
        val now = System.currentTimeMillis()
        val events = um.queryEvents(now - WINDOW_MS, now) ?: return
        val e = UsageEvents.Event()
        var pkg: String? = null
        var cls: String? = null
        var ts = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            everSawEvent = true
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                pkg = e.packageName; cls = e.className; ts = e.timeStamp
            }
        }
        if (!everSawEvent && !loggedNoAccess && !hasUsageAccess()) {
            loggedNoAccess = true
            Log.w(TAG, "no usage-stats access; run: appops set $packageName GET_USAGE_STATS allow")
        }
        if (policy.decide(pkg, cls, ts, now, lastMoveAt, true) != KeeperPolicy.Action.MOVE_TO_FRONT) return
        lastMoveAt = now
        bringToFront()
        Log.i(TAG, "move from=$pkg latency=${System.currentTimeMillis() - ts}ms")
    }

    /** Runs on the worker thread. Only moves an existing app task; never starts an activity. */
    private fun bringToFront() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (t in am.appTasks) {
                val info = t.taskInfo
                if (info.topActivity == null && info.baseActivity == null) continue
                t.moveToFront()
                return
            }
            // No app task yet: a normal HOME launch from the user will create it. Do nothing.
        } catch (t: Throwable) {
            Log.w(TAG, "moveToFront: $t")
        }
    }

    companion object {
        private const val TAG = "FTVKeeper"
        private const val CHANNEL = "keeper"
        private const val NOTIF_ID = 1
        private const val POLL_MS = 250L
        private const val WINDOW_MS = 5000L

        @Volatile var running = false

        fun start(c: Context) {
            if (running) return
            val i = Intent(c, Keeper::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, Keeper::class.java))
        }
    }
}
