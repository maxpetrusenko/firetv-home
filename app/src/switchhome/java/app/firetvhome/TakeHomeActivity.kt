package app.firetvhome

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The single launcher tile: claim the Home role, open the home screen. Does no waiting: it hands
 * off and finishes inside onCreate. MainActivity is singleTask, so NEW_TASK alone reuses the
 * existing home task instead of creating another one.
 */
class TakeHomeActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        HomeRole.take(this)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
        overridePendingTransition(0, 0)
    }
}
