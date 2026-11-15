package com.example.cast

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.TextView

/**
 * Shared "liquid glass" look: a soft glowing backdrop with frosted translucent panels,
 * a bright rim and a gloss sheen. Focus (remote control) brightens the panel and scales it up slightly.
 * Only plain drawables are used, so it is cheap enough for a Fire TV Stick Lite.
 */
object Glass {
    val ACCENT: Int = Color.parseColor("#5EEAD4")
    val TEXT: Int = Color.argb(245, 255, 255, 255)
    val SUB: Int = Color.argb(160, 255, 255, 255)
    val FAINT: Int = Color.argb(110, 255, 255, 255)

    val light: Typeface get() = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val medium: Typeface get() = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()
    private fun w(a: Int) = Color.argb(a, 255, 255, 255)

    /** One glass surface. [tint] makes it an accent (primary) surface. */
    private fun surface(c: Context, radiusDp: Int, focused: Boolean, tint: Int?): Drawable {
        val r = dp(c, radiusDp).toFloat()
        val colors = if (tint != null)
            intArrayOf(Color.argb(if (focused) 255 else 205, Color.red(tint), Color.green(tint), Color.blue(tint)),
                Color.argb(if (focused) 235 else 160, Color.red(tint) / 2, Color.green(tint) * 3 / 4, Color.blue(tint) * 3 / 4))
        else if (focused) intArrayOf(w(80), w(40)) else intArrayOf(w(44), w(14))
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadius = r
            setStroke(dp(c, if (focused) 3 else 1), if (focused) w(235) else w(64))
        }
        val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(w(if (focused) 70 else 52), w(0), w(0))).apply { cornerRadius = r }
        return LayerDrawable(arrayOf(base, sheen)).also { it.setLayerInset(1, dp(c, 2), dp(c, 2), dp(c, 2), dp(c, 2)) }
    }

    /** Static glass panel (not focusable). */
    fun panel(c: Context, radiusDp: Int = 28): Drawable = surface(c, radiusDp, false, null)

    /** Glass that lights up when focused. */
    fun focusable(c: Context, radiusDp: Int = 28, tint: Int? = null): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), surface(c, radiusDp, true, tint))
        addState(intArrayOf(android.R.attr.state_pressed), surface(c, radiusDp, true, tint))
        addState(intArrayOf(), surface(c, radiusDp, false, tint))
    }

    /** Gentle zoom on focus, for TV navigation. */
    fun focusZoom(v: View, scale: Float = 1.05f) {
        val prev = v.onFocusChangeListener
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).setDuration(160).start()
            prev?.onFocusChange(view, has)
        }
    }

    fun pill(c: Context, label: String, primary: Boolean): Button = Button(c).apply {
        text = label; textSize = 19f; isAllCaps = false; setTextColor(Color.WHITE); typeface = medium
        stateListAnimator = null; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(dp(c, 34), dp(c, 15), dp(c, 34), dp(c, 15))
        background = focusable(c, 40, if (primary) Color.parseColor("#2DD4BF") else null)
        focusZoom(this)
    }

    fun chip(c: Context, label: String): TextView = TextView(c).apply {
        text = label; textSize = 15f; setTextColor(SUB)
        setPadding(dp(c, 18), dp(c, 8), dp(c, 18), dp(c, 8))
        background = panel(c, 40)
    }

    // ---- network info ----

    /** This TV's own name (as shown in Fire TV settings). */
    fun tvName(c: Context): String =
        (try { android.provider.Settings.Global.getString(c.contentResolver, "device_name") } catch (_: Exception) { null })
            ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL ?: "Fire TV"

    /** (network name, band label like "5 GHz"). The name needs location permission on newer Android, so it can be generic. */
    fun wifi(c: Context): Pair<String, String> {
        try {
            val cm = c.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            if (cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_ETHERNET) return "Wired network" to "LAN"
            val wifi = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wifi.connectionInfo
            val ssid = info?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" && it != "0x" } ?: "Wi-Fi"
            val mhz = info?.frequency ?: 0
            val band = when { mhz >= 5900 -> "6 GHz"; mhz >= 4900 -> "5 GHz"; mhz > 0 -> "2.4 GHz"; else -> "" }
            return ssid to band
        } catch (_: Exception) {
            return "Wi-Fi" to ""
        }
    }
}

/** Deep blue-black base with soft glowing colour blobs: this is what the glass panels "refract". */
class BackdropView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun blob(c: Canvas, cx: Float, cy: Float, r: Float, rgb: Int, alpha: Int) {
        p.shader = RadialGradient(cx, cy, r, intArrayOf(Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb)),
            Color.argb(0, Color.red(rgb), Color.green(rgb), Color.blue(rgb))), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.shader = LinearGradient(0f, 0f, w, h, Color.parseColor("#060A15"), Color.parseColor("#0F1832"), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        blob(c, w * .10f, h * .15f, w * .46f, Color.parseColor("#14B8A6"), 120)
        blob(c, w * .90f, h * .85f, w * .52f, Color.parseColor("#7C5CFF"), 110)
        blob(c, w * .75f, h * .05f, w * .34f, Color.parseColor("#3B82F6"), 90)
        blob(c, w * .15f, h * .98f, w * .30f, Color.parseColor("#EC4899"), 60)
    }
}

/** Little monitor outline used on PC cards and in the radar. */
class MonitorIconView(context: Context, private val color: Int = Color.WHITE) : View(context) {
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        s.color = color; s.strokeWidth = w * 0.05f
        c.drawRoundRect(RectF(w * .08f, h * .12f, w * .92f, h * .66f), w * .07f, w * .07f, s)
        c.drawLine(w * .5f, h * .66f, w * .5f, h * .84f, s)
        c.drawLine(w * .3f, h * .86f, w * .7f, h * .86f, s)
    }
}

/** "Searching" animation: rings that expand and fade around a monitor icon. */
class RadarView(context: Context) : View(context) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val icon = MonitorIconView(context, Glass.ACCENT)
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val max = minOf(width, height) / 2f
        ring.strokeWidth = Glass.dp(context, 2).toFloat()
        for (i in 0 until 3) {
            val f = (t + i / 3f) % 1f
            ring.color = Color.argb(((1f - f) * 120).toInt(), 94, 234, 212)
            c.drawCircle(cx, cy, max * (0.30f + 0.70f * f), ring)
        }
        val s = (max * 0.62f).toInt()
        icon.layout(0, 0, s, s)
        c.save(); c.translate(cx - s / 2f, cy - s / 2f); icon.draw(c); c.restore()
    }
}
