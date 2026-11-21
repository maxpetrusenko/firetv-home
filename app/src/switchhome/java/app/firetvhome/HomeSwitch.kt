package app.firetvhome

import android.app.Activity
import android.content.Intent
import android.view.View

/** Experimental build (-PswitchHome=true): "Switch home" tile and Keeper start. */
object HomeSwitch {
    /** Off the main thread: the role flag read can hit disk and startForegroundService is a binder call. */
    fun onResume(a: Activity) {
        if (Keeper.running) return
        val app = a.applicationContext
        Thread { if (HomeRole.isActive(app)) Keeper.start(app) }.start()
    }

    fun tile(a: Activity): View? =
        Ui.tile(a, "Switch home", 98, { a.startActivity(Intent(a, ReleaseHomeActivity::class.java)) }, iconRes = R.drawable.ic_apps)
}
