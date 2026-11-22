package com.example.castsender

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.cast.core.BackdropView
import com.example.cast.core.Glass
import com.example.cast.core.MonitorIconView
import com.example.cast.core.PcDiscovery
import com.example.cast.core.RadarView

/**
 * Cast Sender: casts this phone's screen to a PC.
 *   1. Home: lists the PCs on the Wi-Fi (found by UDP broadcast), or type an address.
 *   2. Pair: enter the 6-digit code shown in the PC app's "Receive" tab, pick a quality.
 *   3. Casting: live status and a Stop button (the notification has one too).
 * The actual capture + streaming happens in [CastService].
 */
class MainActivity : Activity() {
    private companion object {
        const val REQ_CAPTURE = 100
        const val REQ_NOTIFY = 101
        const val REQ_MIC = 102
        const val PREFS = "cast_sender_prefs"
        val BG = Color.parseColor("#060A15")
    }

    private enum class Screen { HOME, PAIR, CASTING }

    private lateinit var discovery: PcDiscovery
    private lateinit var content: FrameLayout
    private var screen = Screen.HOME
    private var homeStatus: TextView? = null
    private var homeList: LinearLayout? = null
    private var castTitle: TextView? = null
    private var castLive: TextView? = null
    private var testSoundBtn: View? = null
    private var shown: List<String> = emptyList()

    // the PC being paired with
    private var targetAddr = ""
    private var targetName = ""
    private var quality = Quality.ALL[1]
    private var codeInput: EditText? = null
    private var pairError: TextView? = null
    private var shareSound = true
    private var askedNotify = false
    private var askedMic = false

    private fun dp(v: Int) = Glass.dp(this, v)
    private val prefs get() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        quality = Quality.of(prefs.getString("quality", null))
        shareSound = prefs.getBoolean("sound", true)

        val root = FrameLayout(this)
        root.addView(BackdropView(this), FrameLayout.LayoutParams(-1, -1))
        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        discovery = PcDiscovery(this, onChanged = { if (screen == Screen.HOME) refreshHomeList() },
            onError = { homeStatus?.text = "Can't search the network – type the PC address below." })
        showHome()
    }

    override fun onResume() {
        super.onResume()
        discovery.start()
        CastState.listener = { onCastStateChanged() }
        onCastStateChanged()
    }

    override fun onPause() {
        super.onPause()
        discovery.stop()
        CastState.listener = null
    }

    override fun onBackPressed() {
        when (screen) {
            Screen.PAIR -> showHome()
            else -> super.onBackPressed()
        }
    }

    /** The service reported progress: follow it (casting screen while active, home when it ended). */
    private fun onCastStateChanged() {
        val active = CastState.phase != CastState.Phase.IDLE
        if (active && screen != Screen.CASTING) showCasting()
        else if (!active && screen == Screen.CASTING) showHome()
        else if (screen == Screen.CASTING) refreshCasting()
    }

    // ---------------- HOME ----------------

    private fun showHome() {
        screen = Screen.HOME
        shown = emptyList()
        content.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(28), dp(24), dp(24)) }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ImageView(this).apply { setImageResource(R.drawable.logo) }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(12) })
        top.addView(TextView(this).apply { text = "Cast Sender"; textSize = 20f; setTextColor(Glass.TEXT); typeface = Glass.light; letterSpacing = 0.08f })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val (ssid, band) = Glass.wifi(this)
        top.addView(Glass.chip(this, listOf(band, ssid).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "Wi-Fi" }))
        col.addView(top)

        col.addView(TextView(this).apply { text = "Cast to PC"; textSize = 36f; setTextColor(Glass.TEXT); typeface = Glass.light; setPadding(0, dp(28), 0, 0) })
        homeStatus = TextView(this).apply { textSize = 16f; setTextColor(Glass.SUB); setPadding(0, dp(6), 0, dp(16)) }
        col.addView(homeStatus)
        if (CastState.message.isNotEmpty()) {
            col.addView(TextView(this).apply {
                text = CastState.message; textSize = 14f; setTextColor(Glass.ACCENT)
                setPadding(dp(16), dp(10), dp(16), dp(10)); background = Glass.panel(this@MainActivity, 20)
            }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(12) })
            CastState.message = ""
        }

        homeList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(homeList) }, LinearLayout.LayoutParams(-1, 0, 1f))

        // connect by address
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Glass.panel(this@MainActivity, 32); setPadding(dp(16), dp(10), dp(10), dp(10))
        }
        val addrInput = EditText(this).apply {
            hint = "Or type an address, e.g. 192.168.1.9:3000"; textSize = 14f; setSingleLine(true)
            setTextColor(Color.WHITE); setHintTextColor(Glass.FAINT); background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("addr", "") ?: "")
        }
        bar.addView(addrInput, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(Glass.pill(this, "Next", true).apply {
            setPadding(dp(22), dp(10), dp(22), dp(10)); textSize = 15f
            setOnClickListener {
                val a = addrInput.text.toString().trim()
                if (a.isEmpty()) homeStatus?.text = "Type the PC's address first." else showPair(a, a)
            }
        })
        col.addView(bar, LinearLayout.LayoutParams(-1, -2))

        content.addView(col, FrameLayout.LayoutParams(-1, -1))
        refreshHomeList()
    }

    private fun refreshHomeList() {
        val list = homeList ?: return
        val pcs = discovery.pcs
        val keys = pcs.values.map { it.addr + "|" + it.name }
        if (keys == shown && list.childCount > 0) return
        shown = keys
        list.removeAllViews()
        if (pcs.isEmpty()) {
            homeStatus?.text = "Looking for PCs on your Wi-Fi…"
            list.addView(RadarView(this), LinearLayout.LayoutParams(dp(220), dp(180)).apply { topMargin = dp(24) })
            list.addView(TextView(this).apply {
                text = "On the PC, open the Cast app.\nBoth devices must be on the same Wi-Fi."
                textSize = 14f; setTextColor(Glass.FAINT); gravity = Gravity.CENTER
            })
        } else {
            homeStatus?.text = "Choose the PC to show your screen on"
            for (pc in pcs.values) list.addView(pcCard(pc), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
    }

    private fun pcCard(pc: PcDiscovery.Pc): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(18))
        isClickable = true; isFocusable = true
        background = Glass.focusable(this@MainActivity, 28)
        addView(MonitorIconView(this@MainActivity, Glass.ACCENT), LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(16) })
        val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this@MainActivity).apply { text = pc.name; textSize = 20f; setTextColor(Glass.TEXT); typeface = Glass.medium; setSingleLine(true) })
        texts.addView(TextView(this@MainActivity).apply { text = pc.addr; textSize = 13f; setTextColor(Glass.SUB) })
        addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@MainActivity).apply { text = "›"; textSize = 28f; setTextColor(Glass.SUB) })
        setOnClickListener { showPair(pc.addr, pc.name) }
    }

    // ---------------- PAIR ----------------

    private fun showPair(addr: String, name: String) {
        screen = Screen.PAIR
        targetAddr = addr; targetName = name
        content.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(28), dp(24), dp(24)) }

        col.addView(Glass.chip(this, "‹  Back").apply { setOnClickListener { showHome() } }, LinearLayout.LayoutParams(-2, -2))
        col.addView(TextView(this).apply { text = "Casting to"; textSize = 15f; setTextColor(Glass.SUB); setPadding(0, dp(28), 0, 0) })
        col.addView(TextView(this).apply { text = name; textSize = 30f; setTextColor(Glass.TEXT); typeface = Glass.light; setSingleLine(true) })
        col.addView(TextView(this).apply {
            text = "On that PC open the Cast app, go to the Receive tab and enter the 6-digit code shown there."
            textSize = 15f; setTextColor(Glass.SUB); setLineSpacing(0f, 1.15f); setPadding(0, dp(14), 0, dp(22))
        })

        val code = EditText(this).apply {
            hint = "------"; textSize = 38f; setTextColor(Color.WHITE); setHintTextColor(Glass.FAINT)
            gravity = Gravity.CENTER; letterSpacing = 0.25f; typeface = Glass.light; setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(InputFilter.LengthFilter(6))
            background = Glass.focusable(this@MainActivity, 28); setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        codeInput = code
        col.addView(code, LinearLayout.LayoutParams(-1, -2))
        pairError = TextView(this).apply { textSize = 14f; setTextColor(Color.parseColor("#FF8A8A")); setPadding(0, dp(10), 0, 0) }
        col.addView(pairError)

        col.addView(TextView(this).apply { text = "Quality"; textSize = 14f; setTextColor(Glass.SUB); setPadding(0, dp(22), 0, dp(8)) })
        val qrow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun drawQuality() {
            qrow.removeAllViews()
            for (q in Quality.ALL) qrow.addView(Glass.pill(this, q.label, q.key == quality.key).apply {
                textSize = 14f; setPadding(dp(8), dp(12), dp(8), dp(12))
                setOnClickListener { quality = q; prefs.edit().putString("quality", q.key).apply(); drawQuality() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        }
        drawQuality()
        col.addView(qrow, LinearLayout.LayoutParams(-1, -2))
        col.addView(TextView(this).apply { text = "Smooth is easiest on the phone's battery and Wi-Fi."; textSize = 12f; setTextColor(Glass.FAINT); setPadding(0, dp(8), 0, 0) })

        // sound: needs Android 10+ (Android's playback capture)
        val canSound = Build.VERSION.SDK_INT >= 29
        val soundRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Glass.panel(this@MainActivity, 28); setPadding(dp(20), dp(12), dp(12), dp(12))
        }
        soundRow.addView(TextView(this).apply {
            text = if (canSound) "Share phone sound" else "Phone sound needs Android 10 or newer"
            textSize = 16f; setTextColor(if (canSound) Glass.TEXT else Glass.SUB)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (canSound) soundRow.addView(Glass.pill(this, if (shareSound) "On" else "Off", shareSound).apply {
            textSize = 14f; setPadding(dp(22), dp(8), dp(22), dp(8))
            setOnClickListener {
                shareSound = !shareSound
                prefs.edit().putBoolean("sound", shareSound).apply()
                text = if (shareSound) "On" else "Off"
                background = Glass.focusable(this@MainActivity, 40, if (shareSound) Color.parseColor("#2DD4BF") else null)
            }
        })
        col.addView(soundRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        if (canSound) col.addView(TextView(this).apply {
            text = "Some apps (streaming apps, some games) block sound capture. The phone keeps playing sound too."
            textSize = 12f; setTextColor(Glass.FAINT); setPadding(0, dp(8), 0, 0)
        })

        col.addView(Glass.pill(this, "Start casting", true).apply {
            textSize = 18f; setPadding(dp(20), dp(16), dp(20), dp(16)); setOnClickListener { startPressed() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(26) })

        content.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }, FrameLayout.LayoutParams(-1, -1))
        code.postDelayed({
            code.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(code, InputMethodManager.SHOW_IMPLICIT)
        }, 250)
    }

    private fun startPressed() {
        val code = codeInput?.text?.toString()?.trim().orEmpty()
        if (code.length != 6) { pairError?.text = "Enter the 6-digit code shown on the PC."; return }
        pairError?.text = ""
        prefs.edit().putString("addr", targetAddr).apply()
        askedNotify = false; askedMic = false
        nextStep()
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Permission steps, one after the other; a "no" never blocks the cast (it just goes without that feature). */
    private fun nextStep() {
        // Android 13+: the "casting" notification
        if (Build.VERSION.SDK_INT >= 33 && !askedNotify && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            askedNotify = true; requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY); return
        }
        // Android 10+: capturing the phone's sound needs the microphone permission
        if (shareSound && Build.VERSION.SDK_INT >= 29 && !askedMic && !granted(Manifest.permission.RECORD_AUDIO)) {
            askedMic = true; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC); return
        }
        askScreenCapture()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFY || requestCode == REQ_MIC) nextStep()
    }

    /** The system dialog: "Start recording or casting with Cast Sender?" */
    private fun askScreenCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION") startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) { pairError?.text = "Screen capture was not allowed, so nothing can be cast."; return }
        val code = codeInput?.text?.toString()?.trim().orEmpty()
        val i = Intent(this, CastService::class.java).setAction(CastService.ACTION_START)
            .putExtra(CastService.EXTRA_ADDR, targetAddr).putExtra(CastService.EXTRA_NAME, targetName)
            .putExtra(CastService.EXTRA_CODE, code).putExtra(CastService.EXTRA_QUALITY, quality.key)
            .putExtra(CastService.EXTRA_AUDIO, shareSound)
            .putExtra(CastService.EXTRA_DATA, data)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        showCasting()
    }

    // ---------------- CASTING ----------------

    private fun showCasting() {
        screen = Screen.CASTING
        content.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = Glass.panel(this@MainActivity, 40); setPadding(dp(28), dp(36), dp(28), dp(32))
        }
        card.addView(MonitorIconView(this, Glass.ACCENT), LinearLayout.LayoutParams(dp(72), dp(72)))
        castTitle = TextView(this).apply { textSize = 24f; setTextColor(Glass.TEXT); typeface = Glass.light; gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(6)) }
        card.addView(castTitle)
        castLive = TextView(this).apply { textSize = 13f; setTextColor(Glass.SUB); gravity = Gravity.CENTER }
        card.addView(castLive)
        card.addView(Glass.pill(this, "Stop casting", false).apply {
            textSize = 17f; setPadding(dp(30), dp(14), dp(30), dp(14))
            background = Glass.focusable(this@MainActivity, 40, Color.parseColor("#E5484D"))
            setOnClickListener { startService(Intent(this@MainActivity, CastService::class.java).setAction(CastService.ACTION_STOP)) }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(28) })
        // check that the PC hears the phone (only shown while sound is really being sent)
        card.addView(Glass.pill(this, "Play test sound", false).apply {
            textSize = 14f; setPadding(dp(22), dp(10), dp(22), dp(10)); visibility = View.GONE; setOnClickListener { TestTone.play() }
            testSoundBtn = this
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        card.addView(TextView(this).apply {
            text = "You can leave this app. A notification keeps the cast running and has a Stop button."
            textSize = 12f; setTextColor(Glass.FAINT); gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0)
        })
        col.addView(card, LinearLayout.LayoutParams(-1, -2))
        content.addView(col, FrameLayout.LayoutParams(-1, -1))
        refreshCasting()
    }

    private fun refreshCasting() {
        val live = CastState.phase == CastState.Phase.CASTING
        castTitle?.text = if (live) "Casting to ${CastState.target}" else "Connecting to ${CastState.target.ifEmpty { targetName }}…"
        castLive?.text = if (live) CastState.detail.ifEmpty { "Live" } else "Waiting for the PC"
        testSoundBtn?.visibility = if (CastState.audioOn) View.VISIBLE else View.GONE
    }
}
