package com.example.cast

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.cast.core.BackdropView
import com.example.cast.core.Glass
import com.example.cast.core.MonitorIconView
import com.example.cast.core.PcDiscovery
import com.example.cast.core.RadarView

/**
 * Home screen. Lists every PC running the Cast app on this network (by name) and lets you pick one.
 * Nothing connects automatically. After you pick a PC, the TV shows a 6-digit code to enter on that PC.
 * You can also type an address by hand (e.g. 192.168.1.20:3000).
 * Finding the PCs is done by the shared [PcDiscovery].
 */
class MainActivity : Activity() {
    companion object {
        const val EXTRA_ADDR = "addr"
        const val EXTRA_NAME = "name"
    }

    private lateinit var discovery: PcDiscovery
    private var shown: List<String> = emptyList()

    private lateinit var status: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var input: EditText

    private fun dp(v: Int) = Glass.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // First launch only: welcome + connect screens (OnboardingActivity marks itself done).
        if (!getSharedPreferences(OnboardingActivity.PREFS, MODE_PRIVATE).getBoolean(OnboardingActivity.DONE_KEY, false))
            startActivity(Intent(this, OnboardingActivity::class.java))

        val root = FrameLayout(this)
        root.addView(BackdropView(this), FrameLayout.LayoutParams(-1, -1))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false
            setPadding(dp(64), dp(40), dp(64), dp(40))
        }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // ---- top bar: app name on the left; this TV and its network on the right ----
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ImageView(this).apply { setImageResource(R.drawable.logo) },
            LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(16) })
        top.addView(TextView(this).apply {
            text = "Cast"; textSize = 28f; setTextColor(Glass.TEXT); typeface = Glass.light; letterSpacing = 0.12f
        })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val (ssid, band) = Glass.wifi(this)
        top.addView(Glass.chip(this, Glass.tvName(this)))
        top.addView(Glass.chip(this, listOf(band, ssid).filter { it.isNotEmpty() }.joinToString(" · ")),
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        col.addView(top)

        // ---- centre: title, hint, PC cards ----
        val centre = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        centre.addView(TextView(this).apply {
            text = "Select your PC"; textSize = 50f; setTextColor(Glass.TEXT); typeface = Glass.light
        })
        status = TextView(this).apply { textSize = 20f; setTextColor(Glass.SUB); setPadding(0, dp(8), 0, dp(24)) }
        centre.addView(status)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            setPadding(dp(12), dp(14), dp(12), dp(14))
        }
        scroll.addView(listBox)
        centre.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = -dp(12) })
        col.addView(centre, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- bottom: connect by address ----
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Glass.panel(this@MainActivity, 36)
            setPadding(dp(32), dp(14), dp(18), dp(14))
        }
        bar.addView(TextView(this).apply { text = "Connect by address"; textSize = 18f; setTextColor(Glass.SUB) },
            LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(20) })
        input = EditText(this).apply {
            hint = "192.168.1.20:3000"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE); setHintTextColor(Glass.FAINT); textSize = 20f; setSingleLine(true)
            background = Glass.focusable(this@MainActivity, 22)
            setPadding(dp(22), dp(10), dp(22), dp(10))
            setText(getSharedPreferences(OnboardingActivity.PREFS, MODE_PRIVATE).getString("addr", "") ?: "")
        }
        bar.addView(input, LinearLayout.LayoutParams(0, dp(54), 1f).apply { rightMargin = dp(14) })
        bar.addView(Glass.pill(this, "Connect", true).apply { setOnClickListener { manual(ReceiverActivity::class.java) } },
            LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
        bar.addView(Glass.pill(this, "Browser mode", false).apply { setOnClickListener { manual(WebActivity::class.java) } })
        col.addView(bar, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
        discovery = PcDiscovery(this, onChanged = { render() },
            onError = { status.text = "Auto-discovery unavailable – type the PC address below." })
        render()
    }

    override fun onResume() {
        super.onResume()
        discovery.start()
    }

    override fun onPause() {
        super.onPause()
        discovery.stop()
    }

    private fun manual(cls: Class<*>) {
        val addr = input.text.toString().trim()
        if (addr.isEmpty()) { status.text = "Type the PC address first."; return }
        launch(cls, addr, addr)
    }

    private fun launch(cls: Class<*>, addr: String, name: String) {
        getSharedPreferences(OnboardingActivity.PREFS, MODE_PRIVATE).edit().putString("addr", addr).apply()
        startActivity(Intent(this, cls).putExtra(EXTRA_ADDR, addr).putExtra(EXTRA_NAME, name))
    }

    // ---- list of PCs ----

    private fun pcCard(pc: PcDiscovery.Pc): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(32), dp(24), dp(32), dp(24))
        isFocusable = true; isClickable = true
        background = Glass.focusable(this@MainActivity, 32)
        Glass.focusZoom(this)
        addView(MonitorIconView(this@MainActivity, Glass.ACCENT), LinearLayout.LayoutParams(dp(64), dp(64)))
        addView(TextView(this@MainActivity).apply {
            text = pc.name; textSize = 26f; setTextColor(Glass.TEXT); typeface = Glass.medium; setSingleLine(true)
            setPadding(0, dp(14), 0, 0)
        })
        addView(TextView(this@MainActivity).apply { text = pc.addr; textSize = 16f; setTextColor(Glass.SUB) })
        setOnClickListener { launch(ReceiverActivity::class.java, pc.addr, pc.name) }
    }

    private fun render() {
        val pcs = discovery.pcs
        val keys = pcs.values.map { it.addr + "|" + it.name }
        val expected = if (keys.isEmpty()) 1 else keys.size
        if (keys == shown && listBox.childCount == expected && status.text.isNotEmpty()) return
        shown = keys
        val hadFocus = listBox.hasFocus()
        listBox.removeAllViews()
        if (pcs.isEmpty()) {
            listBox.addView(RadarView(this), LinearLayout.LayoutParams(dp(240), dp(190)))
            status.text = "Looking for PCs on your network…"
        } else {
            for (pc in pcs.values)
                listBox.addView(pcCard(pc), LinearLayout.LayoutParams(dp(300), dp(190)).apply { rightMargin = dp(20) })
            status.text = "Choose the PC you want to cast from"
            // take focus unless the user is already typing / using the buttons below
            if (hadFocus || currentFocus == null || (currentFocus === input && input.text.isEmpty())) listBox.getChildAt(0)?.requestFocus()
        }
    }
}
