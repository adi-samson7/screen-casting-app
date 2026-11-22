package com.example.cast.core

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Finds PCs running the Cast app on this network. Each PC announces itself with a UDP broadcast
 * "CASTAPP|<port>|<name>" to port 41234 every 2 seconds; a PC that stops announcing is dropped after 7 seconds.
 *
 * Use from the main thread only. [onChanged] fires when a PC appears, is renamed, or disappears.
 * [onError] fires if the listening socket cannot be opened (the caller can fall back to typing an address).
 */
class PcDiscovery(
    private val context: Context,
    private val onChanged: () -> Unit,
    private val onError: () -> Unit = {},
) {
    class Pc(val name: String, val addr: String, var seen: Long)

    companion object {
        const val PORT = 41234
        private const val TTL_MS = 7000L
    }

    /** Current list, in the order PCs were first seen. */
    val pcs = LinkedHashMap<String, Pc>()

    private val ui = Handler(Looper.getMainLooper())
    private var socket: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    @Volatile private var running = false

    private val prune = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (pcs.values.removeAll { now - it.seen > TTL_MS }) onChanged()
            ui.postDelayed(this, 2000)
        }
    }

    fun start() {
        if (running) return
        running = true
        ui.post(prune)
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wifi.createMulticastLock("cast-discovery").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {}
        Thread {
            try {
                val s = DatagramSocket(null)
                s.reuseAddress = true
                s.bind(InetSocketAddress(PORT))
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
                        val old = pcs[addr]
                        if (old == null || old.name != name) { pcs[addr] = Pc(name, addr, SystemClock.elapsedRealtime()); onChanged() }
                        else old.seen = SystemClock.elapsedRealtime()
                    }
                }
            } catch (e: Exception) {
                if (running) ui.post { onError() }
            }
        }.start()
    }

    fun stop() {
        running = false
        ui.removeCallbacks(prune)
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        try { lock?.release() } catch (_: Exception) {}
        lock = null
    }
}
