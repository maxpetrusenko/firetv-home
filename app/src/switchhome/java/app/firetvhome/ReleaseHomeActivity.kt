package app.firetvhome

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle

/** Give the Home role back: stop the Keeper, then open dumbtv home (or Amazon's if absent). Finishes inside onCreate. */
class ReleaseHomeActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        HomeRole.release(this)
        val dumb = Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName("tv.dumbtv.home", "tv.dumbtv.home.Home"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(dumb)
        } catch (e: Exception) {
            val amazon = packageManager.getLaunchIntentForPackage("com.amazon.tv.launcher")
            if (amazon != null) try { startActivity(amazon.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (x: Exception) { }
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
