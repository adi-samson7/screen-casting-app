package com.example.castsender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import com.example.cast.core.SdpAdapter
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.TimeUnit

/**
 * Runs a cast: captures this phone's screen (MediaProjection), encodes it with the hardware H.264 encoder and sends it to a
 * PC over WebRTC. It speaks the same signaling messages as the PC and TV apps (join / offer / answer / candidate / bye),
 * so the PC's "Receive" tab (receiver.html) can show it.
 *
 * Android requires screen capture to run inside a foreground service with a visible notification (which also has a Stop button).
 */
class CastService : Service() {
    companion object {
        const val ACTION_START = "com.example.castsender.START"
        const val ACTION_STOP = "com.example.castsender.STOP"
        const val EXTRA_ADDR = "addr"          // "192.168.1.9:3000"
        const val EXTRA_NAME = "name"          // the PC's name
        const val EXTRA_CODE = "code"          // 6-digit code shown on the PC
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_DATA = "projection"    // result Intent of the screen-capture permission dialog
        private const val CHANNEL = "cast"
        private const val NOTIF_ID = 1
        private const val TAG = "CastService"
    }

    private val ui = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()

    private lateinit var addr: String
    private lateinit var pcName: String
    private lateinit var code: String
    private lateinit var quality: Quality
    private var projectionData: Intent? = null

    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var capturer: ProjectionCapturer? = null
    private var pc: PeerConnection? = null
    private var ws: WebSocket? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    private val pendingIce = ArrayList<IceCandidate>()
    private var remoteSet = false
    private var stopped = false
    private var curW = 0
    private var curH = 0
    private var lastBytes = 0L
    private var lastStatsAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopCasting("Stopped"); return START_NOT_STICKY }
            ACTION_START -> {}
            else -> { stopSelf(); return START_NOT_STICKY }
        }
        if (CastState.phase != CastState.Phase.IDLE) return START_NOT_STICKY       // a cast is already running
        stopped = false
        addr = intent.getStringExtra(EXTRA_ADDR) ?: run { stopSelf(); return START_NOT_STICKY }
        pcName = intent.getStringExtra(EXTRA_NAME) ?: addr
        code = intent.getStringExtra(EXTRA_CODE) ?: ""
        quality = Quality.of(intent.getStringExtra(EXTRA_QUALITY))
        projectionData = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)

        // Must be in the foreground BEFORE the screen capture starts (Android 10+; mandatory on 14+).
        startForegroundNotification()
        CastState.update { phase = CastState.Phase.CONNECTING; target = pcName; detail = ""; message = "" }
        try { wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "cast-sender").apply { acquire() } } catch (_: Exception) {}
        connect()
        return START_NOT_STICKY
    }

    // ---------------- notification ----------------

    private fun startForegroundNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Casting", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, CastService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = builder
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Casting your screen to $pcName")
            .setContentText("Tap to open, or press Stop")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notify), "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIF_ID, n)
    }

    // ---------------- signaling ----------------

    private fun send(o: JSONObject) { ws?.send(o.toString()) }

    private fun connect() {
        val socket = http.newWebSocket(Request.Builder().url("ws://$addr").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ui.post { if (!stopped) send(JSONObject().put("type", "join").put("code", code)) }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.i(TAG, "ws message: ${text.take(60)}")
                ui.post { if (!stopped) handle(JSONObject(text)) }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "ws closed: $code $reason")
                ui.post { stopCasting("Disconnected from the PC") }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure: $t (http ${response?.code})")
                ui.post { stopCasting("Could not reach the PC (same Wi-Fi?)") }
            }
        })
        ws = socket
    }

    private fun handle(m: JSONObject) {
        when (m.optString("type")) {
            "joined" -> startMedia()                                   // the PC accepted our code: now capture and send
            "error" -> stopCasting(m.optString("message", "The PC refused the code"))
            "answer" -> onAnswer(m.getJSONObject("answer").getString("sdp"))
            "candidate" -> addRemoteCandidate(m.getJSONObject("candidate"))
            "bye" -> stopCasting("The PC ended the cast")
            // audio-output / audio-state / stats-visible are for TV receivers: ignored here
        }
    }

    // ---------------- capture + WebRTC ----------------

    /** Capture size: the phone's real screen shape, with the longer edge limited by the quality preset (even numbers). */
    private fun captureSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        val display = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION") display.getRealMetrics(dm)
        val scale = minOf(1.0, quality.longSide.toDouble() / maxOf(dm.widthPixels, dm.heightPixels))
        fun even(v: Double) = maxOf(2, (v.toInt() / 2) * 2)
        return even(dm.widthPixels * scale) to even(dm.heightPixels * scale)
    }

    private fun startMedia() {
        try {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions())
            val eglBase = EglBase.create().also { egl = it }
            val f = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))   // hardware H.264 when available
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                .createPeerConnectionFactory()
            factory = f

            val (w, h) = captureSize()
            curW = w; curH = h
            val st = SurfaceTextureHelper.create("ScreenCapture", eglBase.eglBaseContext).also { helper = it }
            val src = f.createVideoSource(false).also { source = it }          // false = treat as normal video (smooth motion)
            val cap = ProjectionCapturer(projectionData!!) { Log.i(TAG, "MediaProjection.onStop"); ui.post { stopCasting("Screen sharing was stopped") } }
            capturer = cap
            cap.initialize(st, applicationContext, src.capturerObserver)
            cap.startCapture(w, h, quality.fps)
            track = f.createVideoTrack("screen", src)
            watchRotation()
            createPeer(f)
        } catch (e: Exception) {
            Log.e(TAG, "startMedia failed", e)
            stopCasting("Could not start screen capture")
        }
    }

    /** Rotating the phone changes the screen shape: resize the capture to match. */
    private fun watchRotation() {
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) {}
            override fun onDisplayRemoved(id: Int) {}
            override fun onDisplayChanged(id: Int) {
                if (id != Display.DEFAULT_DISPLAY || stopped) return
                val (w, h) = captureSize()
                if (w != curW || h != curH) {
                    Log.i(TAG, "screen changed: ${curW}x$curH -> ${w}x$h, resizing capture")
                    curW = w; curH = h
                    try { capturer?.changeCaptureFormat(w, h, quality.fps) } catch (e: Exception) { Log.w(TAG, "resize failed", e) }
                }
            }
        }
        displayListener = l
        dm.registerDisplayListener(l, ui)
    }

    private fun createPeer(f: PeerConnectionFactory) {
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {          // LAN only: no STUN / TURN
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val p = f.createPeerConnection(config, observer) ?: run { stopCasting("Could not create the connection"); return }
        pc = p
        val sender = p.addTrack(track, listOf("cast"))

        // Prefer H.264 (the PC decodes it in hardware); keep the other codecs as fallback.
        try {
            val tr = p.transceivers.firstOrNull { it.sender.id() == sender.id() }
            val codecs = f.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
            val h264 = codecs.filter { it.name.equals("H264", ignoreCase = true) }
            if (tr != null && h264.isNotEmpty()) tr.setCodecPreferences(h264 + codecs.filter { !it.name.equals("H264", ignoreCase = true) })
        } catch (e: Throwable) { Log.w(TAG, "H.264 preference not applied", e) }

        // Bitrate / frame rate cap; keep full resolution and drop frames instead when the network is tight.
        try {
            val prm = sender.parameters
            prm.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            for (e in prm.encodings) { e.maxBitrateBps = quality.bps; e.maxFramerate = quality.fps; e.scaleResolutionDownBy = 1.0 }
            sender.setParameters(prm)
        } catch (e: Throwable) { Log.w(TAG, "sender parameters not applied", e) }

        p.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(d: SessionDescription) {
                p.setLocalDescription(object : SdpAdapter() {
                    override fun onSetSuccess() {
                        ui.post {
                            if (stopped) return@post
                            send(JSONObject().put("type", "offer")
                                .put("offer", JSONObject().put("type", "offer").put("sdp", d.description)))
                        }
                    }
                }, d)
            }
            override fun onCreateFailure(e: String?) { ui.post { stopCasting("Could not start the connection") } }
        }, MediaConstraints())
        ui.postDelayed(statsTick, 2000)
    }

    /** Ask the encoder to start at (and stay near) the target bitrate instead of probing up from a low guess. */
    private fun boostBitrate(sdp: String): String {
        val kbps = quality.bps / 1000
        var out = sdp
        for (m in Regex("a=rtpmap:(\\d+) H264/90000").findAll(sdp)) {
            val id = m.groupValues[1]
            out = out.replace(Regex("(a=fmtp:$id [^\\r\\n]*)"),
                "$1;x-google-start-bitrate=$kbps;x-google-min-bitrate=${kbps * 2 / 5};x-google-max-bitrate=$kbps")
        }
        return out
    }

    private fun onAnswer(sdp: String) {
        val p = pc ?: return
        p.setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() {
                ui.post {
                    if (stopped || pc !== p) return@post
                    remoteSet = true
                    pendingIce.forEach { p.addIceCandidate(it) }
                    pendingIce.clear()
                }
            }
            override fun onSetFailure(e: String?) { ui.post { stopCasting("The PC's answer was not accepted") } }
        }, SessionDescription(SessionDescription.Type.ANSWER, boostBitrate(sdp)))
    }

    private fun addRemoteCandidate(c: JSONObject) {
        val sdp = c.optString("candidate")
        if (sdp.isEmpty()) return
        val ice = IceCandidate(c.optString("sdpMid", "0"), c.optInt("sdpMLineIndex", 0), sdp)
        val p = pc
        if (p != null && remoteSet) p.addIceCandidate(ice) else pendingIce.add(ice)
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            ui.post {
                send(JSONObject().put("type", "candidate").put("candidate",
                    JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)))
            }
        }
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
            ui.post {
                when (s) {
                    PeerConnection.PeerConnectionState.CONNECTED -> CastState.update { phase = CastState.Phase.CASTING }
                    PeerConnection.PeerConnectionState.FAILED -> stopCasting("The connection to the PC failed")
                    else -> {}
                }
            }
        }
        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onAddStream(s: org.webrtc.MediaStream) {}
        override fun onRemoveStream(s: org.webrtc.MediaStream) {}
        override fun onDataChannel(d: org.webrtc.DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(r: org.webrtc.RtpReceiver, s: Array<out org.webrtc.MediaStream>) {}
    }

    /** Every 2 s: read the sender statistics for the on-screen line (size, fps, bitrate, encoder). */
    private val statsTick = object : Runnable {
        override fun run() {
            val p = pc
            if (p != null && !stopped) {
                p.getStats { report ->
                    for (s in report.statsMap.values) {
                        if (s.type != "outbound-rtp" || s.members["kind"] != "video") continue
                        val bytes = (s.members["bytesSent"] as? Number)?.toLong() ?: 0L
                        val now = System.currentTimeMillis()
                        val kbps = if (lastStatsAt > 0 && now > lastStatsAt) (bytes - lastBytes) * 8 / (now - lastStatsAt) else 0
                        lastBytes = bytes; lastStatsAt = now
                        val enc = (s.members["encoderImplementation"] ?: "?").toString()
                        val line = "${s.members["frameWidth"] ?: 0}×${s.members["frameHeight"] ?: 0} · " +
                            "${(s.members["framesPerSecond"] as? Number)?.toInt() ?: 0} fps · " +
                            (if (kbps >= 1000) "%.1f Mbps".format(kbps / 1000.0) else "$kbps kbps") + " · $enc"
                        CastState.update { detail = line }
                    }
                }
            }
            ui.postDelayed(this, 2000)
        }
    }

    // ---------------- shutdown ----------------

    private fun stopCasting(reason: String) {
        if (stopped) return
        stopped = true
        Log.i(TAG, "stopCasting: $reason")
        ui.removeCallbacksAndMessages(null)
        try { ws?.send("""{"type":"bye"}"""); ws?.close(1000, null) } catch (_: Exception) {}
        ws = null
        try { displayListener?.let { (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(it) } } catch (_: Exception) {}
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        try { track?.dispose() } catch (_: Exception) {}
        try { source?.dispose() } catch (_: Exception) {}
        try { helper?.dispose() } catch (_: Exception) {}
        try { pc?.close(); pc?.dispose() } catch (_: Exception) {}
        try { factory?.dispose() } catch (_: Exception) {}
        try { egl?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        capturer = null; track = null; source = null; helper = null; pc = null; factory = null; egl = null; wifiLock = null
        pendingIce.clear(); remoteSet = false; lastBytes = 0; lastStatsAt = 0
        CastState.update { phase = CastState.Phase.IDLE; detail = ""; message = reason }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopCasting("Stopped")
        http.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
