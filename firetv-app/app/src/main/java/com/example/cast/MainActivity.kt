package com.example.cast

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Start screen. Lists every PC running the Cast app on this network (by name) and lets you pick one.
 * Nothing connects automatically. After you pick a PC, the TV shows a 6-digit code to enter on that PC.
 * You can also type an address by hand (e.g. 192.168.1.20:3000).
 */
class MainActivity : Activity() {
    companion object {
        const val DISCOVERY_PORT = 41234
        const val EXTRA_ADDR = "addr"
        const val EXTRA_NAME = "name"
        private const val TTL_MS = 7000L
    }

    private class Pc(val name: String, val addr: String, var seen: Long)

    private val ui = Handler(Looper.getMainLooper())
    private val pcs = LinkedHashMap<String, Pc>()
    private var shown: List<String> = emptyList()

    private lateinit var status: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var input: EditText
    private var socket: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    @Volatile private var running = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(48), dp(32), dp(48), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "Cast Receiver"; textSize = 34f; setTextColor(Color.WHITE)
        })
        status = TextView(this).apply {
            textSize = 20f; setTextColor(Color.parseColor("#99AAAA")); setPadding(0, dp(8), 0, dp(16))
        }
        root.addView(status)

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox, LinearLayout.LayoutParams(dp(620), LinearLayout.LayoutParams.WRAP_CONTENT))

        root.addView(TextView(this).apply {
            text = "Or enter an address"; textSize = 16f; setTextColor(Color.parseColor("#667788")); setPadding(0, dp(24), 0, 0)
        })
        input = EditText(this).apply {
            hint = "e.g. 192.168.1.20:3000"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#777777")); textSize = 22f
            setSingleLine(true)
            setText(getPreferences(MODE_PRIVATE).getString("addr", "") ?: "")
        }
        root.addView(input, LinearLayout.LayoutParams(dp(620), LinearLayout.LayoutParams.WRAP_CONTENT))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Connect"
            setOnClickListener { manual(ReceiverActivity::class.java) }
        })
        row.addView(Button(this).apply {
            text = "Browser mode"
            setOnClickListener { manual(WebActivity::class.java) }
        })
        root.addView(row)
        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        startDiscovery()
        ui.post(prune)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(prune)
        stopDiscovery()
    }

    private fun manual(cls: Class<*>) {
        val addr = input.text.toString().trim()
        if (addr.isEmpty()) { status.text = "Type the PC address first."; return }
        launch(cls, addr, addr)
    }

    private fun launch(cls: Class<*>, addr: String, name: String) {
        getPreferences(MODE_PRIVATE).edit().putString("addr", addr).apply()
        startActivity(Intent(this, cls).putExtra(EXTRA_ADDR, addr).putExtra(EXTRA_NAME, name))
    }

    // ---- list of PCs ----

    private val prune = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (pcs.values.removeAll { now - it.seen > TTL_MS }) render()
            ui.postDelayed(this, 2000)
        }
    }

    private fun render() {
        val keys = pcs.values.map { it.addr + "|" + it.name }
        if (keys == shown && listBox.childCount == keys.size) return
        shown = keys
        val hadFocus = listBox.hasFocus()
        listBox.removeAllViews()
        for (pc in pcs.values) {
            listBox.addView(Button(this).apply {
                text = "🖥  ${pc.name}\n${pc.addr}"
                textSize = 22f; isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(24), dp(12), dp(24), dp(12))
                setOnClickListener { launch(ReceiverActivity::class.java, pc.addr, pc.name) }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        status.text = if (pcs.isEmpty()) "Looking for PCs on your network…" else "Select your PC"
        if (pcs.isNotEmpty() && (hadFocus || currentFocus == null)) listBox.getChildAt(0)?.requestFocus()
    }

    // ---- discovery (UDP beacon "CASTAPP|<port>|<name>") ----

    private fun startDiscovery() {
        if (running) return
        running = true
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wifi.createMulticastLock("cast-discovery").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {}
        Thread {
            try {
                val s = DatagramSocket(null)
                s.reuseAddress = true
                s.bind(InetSocketAddress(DISCOVERY_PORT))
                socket = s
                val buf = ByteArray(256)
                while (running) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val parts = String(p.data, 0, p.length).split("|", limit = 3)
                    if (parts.size < 2 || parts[0] != "CASTAPP") continue
                    val port = parts[1].trim().toIntOrNull() ?: continue
                    val host = p.address.hostAddress ?: continue
                    val name = parts.getOrNull(2)?.trim().takeUnless { it.isNullOrEmpty() } ?: host
                    val addr = "$host:$port"
                    ui.post {
                        val now = SystemClock.elapsedRealtime()
                        val old = pcs[addr]
                        if (old == null || old.name != name) pcs[addr] = Pc(name, addr, now) else old.seen = now
                        render()
                    }
                }
            } catch (e: Exception) {
                if (running) ui.post { status.text = "Auto-discovery unavailable – type the PC address below." }
            }
        }.start()
    }

    private fun stopDiscovery() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        try { lock?.release() } catch (_: Exception) {}
        lock = null
    }
}
