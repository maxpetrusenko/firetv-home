package app.firetvhome

import android.app.Activity
import android.view.View

/** Default build: home switching is compiled out. No tile, no background work. */
object HomeSwitch {
    fun onResume(a: Activity) {}
    fun tile(a: Activity): View? = null
}
