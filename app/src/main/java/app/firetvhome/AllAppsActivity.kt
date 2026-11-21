package app.firetvhome

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

class AllAppsActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pm = packageManager
        val seen = HashSet<String>()
        val apps = ArrayList<Pair<String, ComponentName>>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                val ai = ri.activityInfo
                if (seen.add(ai.packageName)) apps.add(ri.loadLabel(pm).toString() to ComponentName(ai.packageName, ai.name))
            }
        }
        apps.sortBy { it.first.lowercase() }

        val cols = 7
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@AllAppsActivity, 40), Ui.dp(this@AllAppsActivity, 28), Ui.dp(this@AllAppsActivity, 28), Ui.dp(this@AllAppsActivity, 28))
        }
        col.addView(Ui.text(this, "All apps", 30f, bold = true), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = Ui.dp(this@AllAppsActivity, 16) })
        var row: LinearLayout? = null
        apps.forEachIndexed { i, (label, cn) ->
            if (i % cols == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(this@AllAppsActivity, 12) })
            }
            val t = Ui.tile(this, label, 118, { Ui.launch(this, cn) }, loader = { pm.getActivityIcon(cn) })
            row!!.addView(t)
        }
        setContentView(Ui.withBackground(this, ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(col, ViewGroup.LayoutParams(-1, -2))
        }, Config.load(this).background))
    }
}
