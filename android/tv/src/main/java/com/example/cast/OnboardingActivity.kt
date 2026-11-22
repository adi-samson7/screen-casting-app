package com.example.cast

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.cast.core.BackdropView
import com.example.cast.core.Glass

/**
 * First-launch onboarding: "Welcome" then "Connect a device". Shown once; MainActivity checks [DONE_KEY].
 * Back on the first step quits the app (onboarding is not marked done, so it shows again next time).
 */
class OnboardingActivity : Activity() {
    companion object {
        const val PREFS = "cast_prefs"
        const val DONE_KEY = "onboarded"
        private val TEAL = Color.parseColor("#3FCFB4")
        private val TEAL_LIGHT = Color.parseColor("#6FE3CD")
        private val TEXT = Color.parseColor("#F4ECE6")
        private val SUB = Color.parseColor("#DCE3E3")
    }

    private var step = 0
    private lateinit var root: FrameLayout
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this).apply { clipChildren = false }
        val page = FrameLayout(this)
        page.addView(BackdropView(this), FrameLayout.LayoutParams(-1, -1))
        page.addView(root, FrameLayout.LayoutParams(-1, -1))
        setContentView(page)
        show(0)
    }

    private fun finishOnboarding() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(DONE_KEY, true).apply()
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (step > 0) show(step - 1) else finishAffinity()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---- screens ----

    private fun show(s: Int) {
        step = s
        root.removeAllViews()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        root.addView(row, FrameLayout.LayoutParams(-1, -1))

        // left: illustration
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        row.addView(left, LinearLayout.LayoutParams(0, -1, 1f))
        // right: text + button
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
            setPadding(dp(24), 0, dp(64), 0)
        }
        row.addView(right, LinearLayout.LayoutParams(0, -1, 1f))

        val title: String; val body: String; val button: String
        // the illustration sits on a frosted glass card
        val card = FrameLayout(this).apply { background = Glass.panel(this@OnboardingActivity, 44); setPadding(dp(40), dp(40), dp(40), dp(40)) }
        if (s == 0) {
            card.addView(IllustrationView(this, welcome = true), FrameLayout.LayoutParams(dp(400), dp(320)))
            left.addView(card, LinearLayout.LayoutParams(-2, -2))
            title = "Welcome"
            body = "Cast Receiver lets this device receive your PC screen and sound over your home Wi-Fi. " +
                "Pick your PC, enter the 6-digit code shown here, and start casting."
            button = "Continue"
        } else {
            val (ssid, band) = Glass.wifi(this)
            card.addView(IllustrationView(this, welcome = false, caption = band.ifEmpty { "Wi-Fi" }), FrameLayout.LayoutParams(dp(300), dp(300)))
            left.addView(card, LinearLayout.LayoutParams(-2, -2))
            left.addView(TextView(this).apply {
                text = if (ssid == "Wi-Fi") "Your Wi-Fi network" else ssid
                textSize = 24f; setTextColor(Glass.TEXT); typeface = Glass.medium
                gravity = Gravity.CENTER; setPadding(dp(16), dp(20), dp(16), 0)
            })
            title = "Connect a device"
            body = "Please make sure the PC you want to share from is connected to the same wireless network as this device."
            button = "Confirm"
        }

        right.addView(TextView(this).apply {
            text = title; textSize = 46f; setTextColor(Glass.TEXT); typeface = Glass.light
        })
        right.addView(TextView(this).apply {
            text = body; textSize = 21f; setTextColor(Glass.SUB); setLineSpacing(0f, 1.15f)
            setPadding(0, dp(18), 0, dp(36))
        })
        val action = Glass.pill(this, button, true).apply {
            textSize = 21f
            setOnClickListener { if (step == 0) show(1) else finishOnboarding() }
        }
        right.addView(action, LinearLayout.LayoutParams(-1, dp(64)))
        action.requestFocus()

        if (s == 0) {   // close / skip
            val close = TextView(this).apply {
                text = "✕"; textSize = 22f; setTextColor(Glass.TEXT); gravity = Gravity.CENTER
                isFocusable = true; isClickable = true
                background = Glass.focusable(this@OnboardingActivity, 28)
                Glass.focusZoom(this)
                setOnClickListener { finishOnboarding() }
            }
            root.addView(close, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(32); rightMargin = dp(40)
            })
        }
    }
}

/** Simple line-art illustrations: a TV with media icons + devices (welcome), or a Wi-Fi symbol in a circle (connect). */
private class IllustrationView(context: Context, private val welcome: Boolean, private val caption: String = "") : View(context) {
    private val white = Color.parseColor("#F4ECE6")
    private val teal = Color.parseColor("#3FCFB4")
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = white; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = white }
    private val tealFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = teal }
    private val tealStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = teal; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#DCE3E3"); textAlign = Paint.Align.CENTER }

    override fun onDraw(c: Canvas) {
        if (welcome) drawWelcome(c) else drawWifi(c)
    }

    private fun drawWelcome(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val u = w / 100f
        // TV
        stroke.strokeWidth = u * 1.6f
        val tv = RectF(w * 0.02f, h * 0.02f, w * 0.98f, h * 0.60f)
        c.drawRect(tv, stroke)
        // media icons inside the TV
        val cy = tv.centerY(); val cx = tv.centerX()
        stroke.strokeWidth = u * 0.8f
        c.drawCircle(cx - u * 21, cy, u * 9, stroke); c.drawCircle(cx + u * 21, cy, u * 9, stroke)
        c.drawCircle(cx, cy, u * 12.5f, stroke)
        val play = Path().apply { moveTo(cx - u * 4, cy - u * 6.5f); lineTo(cx - u * 4, cy + u * 6.5f); lineTo(cx + u * 7, cy); close() }
        c.drawPath(play, fill)
        text.textSize = u * 10f; c.drawText("♪", cx - u * 21, cy + u * 3.5f, text)
        val mtn = Path().apply { moveTo(cx + u * 15, cy + u * 4); lineTo(cx + u * 20, cy - u * 3); lineTo(cx + u * 23, cy + u * 1); lineTo(cx + u * 25, cy - u * 1); lineTo(cx + u * 27, cy + u * 4); close() }
        c.drawPath(mtn, fill); c.drawCircle(cx + u * 18, cy - u * 4.5f, u * 1.4f, fill)
        // devices: phone, tablet, laptop
        stroke.strokeWidth = u * 1.2f
        c.drawRoundRect(RectF(w * 0.03f, h * 0.76f, w * 0.17f, h * 0.99f), u * 1.5f, u * 1.5f, stroke)
        c.drawRoundRect(RectF(w * 0.22f, h * 0.70f, w * 0.60f, h * 0.99f), u * 1.5f, u * 1.5f, stroke)
        c.drawRect(RectF(w * 0.66f, h * 0.68f, w * 0.96f, h * 0.93f), stroke)
        c.drawLine(w * 0.62f, h * 0.99f, w * 0.99f, h * 0.99f, stroke)
        // little "waves" above each device
        tealStroke.strokeWidth = u * 0.9f
        for (x in floatArrayOf(0.10f, 0.41f, 0.81f)) {
            val y = if (x < 0.2f) 0.74f else if (x < 0.5f) 0.68f else 0.66f
            c.drawArc(RectF(w * x - u * 4, h * y - u * 3, w * x + u * 4, h * y + u * 3), 200f, 140f, false, tealStroke)
        }
        // small play on tablet, note on phone, image on laptop
        val tp = Path().apply { moveTo(w * 0.38f, h * 0.80f); lineTo(w * 0.38f, h * 0.90f); lineTo(w * 0.45f, h * 0.85f); close() }
        c.drawPath(tp, fill)
        text.textSize = u * 6f; c.drawText("♪", w * 0.10f, h * 0.90f, text)
        c.drawCircle(w * 0.80f, h * 0.78f, u * 1.6f, fill)
        c.drawPath(Path().apply { moveTo(w * 0.72f, h * 0.88f); lineTo(w * 0.79f, h * 0.80f); lineTo(w * 0.83f, h * 0.86f); lineTo(w * 0.86f, h * 0.83f); lineTo(w * 0.90f, h * 0.88f); close() }, fill)
    }

    private fun drawWifi(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val u = w / 100f
        val cx = w / 2; val cy = h / 2
        stroke.strokeWidth = u * 0.5f; stroke.color = Color.parseColor("#66F4ECE6")
        c.drawCircle(cx, cy, u * 48, stroke)
        stroke.color = white
        tealStroke.strokeWidth = u * 6f
        val base = cy + u * 12
        for (r in floatArrayOf(34f, 21f)) {
            c.drawArc(RectF(cx - u * r, base - u * r, cx + u * r, base + u * r), 225f, 90f, false, tealStroke)
        }
        c.drawCircle(cx, base - u * 2, u * 5.5f, tealFill)
        text.textSize = u * 8.5f; c.drawText(caption, cx, cy + u * 33, text)
    }
}
