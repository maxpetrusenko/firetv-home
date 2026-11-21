package app.firetvhome

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

object Ui {
    const val BG = 0xFF101014.toInt()
    const val CARD = 0x8C000000.toInt()
    const val LINE = 0x33FFFFFF
    const val TEXT = 0xFFFFFFFF.toInt()
    const val DIM = 0xFFCFCFD6.toInt()
    const val ACCENT = 0xFF6CB8FF.toInt()
    const val FOCUS_FILL = 0x66FFFFFF
    const val FOCUS_STROKE = 0xFFFFFFFF.toInt()

    private var photo: Bitmap? = null
    private var photoName = ""

    /** Photo background (decoded once, RGB_565, downsampled to the screen) under a dark gradient, with [content] on top. */
    fun withBackground(c: Context, content: View, bgName: String = Config.BG_DEFAULT): View {
        val root = FrameLayout(c)
        root.setBackgroundColor(BG)
        if (photoName != bgName) { photo = null; photoName = bgName }
        val res = if (bgName == Config.BG_BALI) R.drawable.bg_bali else R.drawable.bg_default
        if (photo == null) try {
            val dm = c.resources.displayMetrics
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(c.resources, res, o)
            var s = 1
            while (o.outWidth / (s * 2) >= dm.widthPixels && o.outHeight / (s * 2) >= dm.heightPixels) s *= 2
            val d = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565; inSampleSize = s }
            photo = BitmapFactory.decodeResource(c.resources, res, d)
        } catch (e: Throwable) { }
        photo?.let { b ->
            root.addView(ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(b) }, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(View(c).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0x8C000000.toInt(), 0x66000000))
        }, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    val io = Executors.newSingleThreadExecutor()
    val main = Handler(Looper.getMainLooper())

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun text(c: Context, s: CharSequence, sp: Float, color: Int = TEXT, bold: Boolean = false, lines: Int = 1) =
        TextView(c).apply {
            text = s; textSize = sp; setTextColor(color)
            if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = lines; ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }

    private fun rect(c: Context, fill: Int, stroke: Int, strokeDp: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill); cornerRadius = dp(c, radiusDp).toFloat(); setStroke(dp(c, strokeDp), stroke)
        }

    fun cardBg(c: Context): Drawable = rect(c, CARD, LINE, 1, 16)

    fun tileBg(c: Context): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rect(c, FOCUS_FILL, FOCUS_STROKE, 3, 14))
        addState(intArrayOf(), rect(c, CARD, LINE, 1, 14))
    }

    /** Focusable icon + label tile, icon optionally loaded off the main thread. */
    fun tile(c: Context, label: String, widthDp: Int, onClick: () -> Unit, iconRes: Int = 0, loader: (() -> Drawable?)? = null): View {
        val t = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isClickable = true
            background = tileBg(c)
            setPadding(dp(c, 8), dp(c, 12), dp(c, 8), dp(c, 10))
            setOnClickListener { onClick() }
        }
        val iv = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        if (iconRes != 0) iv.setImageResource(iconRes)
        t.addView(iv, LinearLayout.LayoutParams(dp(c, 56), dp(c, 56)))
        val tv = text(c, label, 15f).apply { gravity = Gravity.CENTER }
        t.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(c, 8) })
        if (loader != null) io.execute {
            val d = try { loader() } catch (e: Exception) { null }
            if (d != null) main.post { iv.setImageDrawable(d) }
        }
        t.layoutParams = LinearLayout.LayoutParams(dp(c, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(c, 8) }
        return t
    }

    fun launchPackage(a: Activity, pkg: String): Boolean {
        val pm = a.packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun launch(a: Activity, cn: ComponentName) {
        try {
            a.startActivity(Intent(Intent.ACTION_MAIN).setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { }
    }

    fun settings(a: Activity) {
        try { a.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
    }

    fun gone(v: View, hide: Boolean) { v.visibility = if (hide) View.GONE else View.VISIBLE }
}
