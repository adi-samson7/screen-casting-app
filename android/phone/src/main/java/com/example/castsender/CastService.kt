package com.example.castsender

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.ExternalAudioProcessingFactory
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
import org.webrtc.audio.JavaAudioDeviceModule
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
        const val ACTION_OUTPUT = "com.example.castsender.OUTPUT"   // change where the sound plays while casting
        const val EXTRA_OUTPUT = "output"      // "phone" | "pc" | "both"
        const val EXTRA_AUDIO = "audio"        // true: also send the phone's sound (Android 10+, needs the microphone permission)
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
    private var audioWanted = false            // sound requested AND allowed
    private var output = "both"              // where the sound plays: the phone, the PC, or both
    private var audioStatus = ""               // shown on the casting screen: "sound on" / "no sound (…)"
    private var playback: PlaybackAudio? = null
    private var adm: JavaAudioDeviceModule? = null
    private var audioProcessing: ExternalAudioProcessingFactory? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var capturer: ProjectionCapturer? = null
    private var pc: PeerConnection? = null
    private var ws: WebSocket? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var pinnedToWifi = false
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
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
            ACTION_OUTPUT -> {                                       // live switch from the casting screen
                if (CastState.phase != CastState.Phase.IDLE && !stopped) applyOutput(intent.getStringExtra(EXTRA_OUTPUT) ?: "both")
                else stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {}
            else -> { stopSelf(); return START_NOT_STICKY }
        }
        if (CastState.phase != CastState.Phase.IDLE) return START_NOT_STICKY       // a cast is already running
        stopped = false
        addr = intent.getStringExtra(EXTRA_ADDR) ?: run { stopSelf(); return START_NOT_STICKY }
        pcName = intent.getStringExtra(EXTRA_NAME) ?: addr
        code = intent.getStringExtra(EXTRA_CODE) ?: ""
        quality = Quality.of(intent.getStringExtra(EXTRA_QUALITY))
        output = intent.getStringExtra(EXTRA_OUTPUT).let { if (it == "pc" || it == "phone") it else "both" }
        val askedForSound = intent.getBooleanExtra(EXTRA_AUDIO, false)
        audioWanted = askedForSound && Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        audioStatus = when {
            audioWanted -> "sound on"
            askedForSound && Build.VERSION.SDK_INT < 29 -> "no sound (needs Android 10+)"
            askedForSound -> "no sound (permission denied)"
            else -> ""
        }
        projectionData = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)

        // Must be in the foreground BEFORE the screen capture starts (Android 10+; mandatory on 14+).
        startForegroundNotification()
        CastState.update { phase = CastState.Phase.CONNECTING; target = pcName; detail = ""; message = "" }
        try { wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "cast-sender").apply { acquire() } } catch (_: Exception) {}
        pinToWifi { connect() }                    // connect only once the Wi-Fi is pinned (or after a short wait)
        return START_NOT_STICKY
    }

    // ---------------- where the sound plays ----------------

    /**
     * "pc": the phone's own speaker is silenced (media volume 0) and the PC plays the sound.
     * "phone": the PC is told to mute, the phone plays as normal.  "both": everything plays.
     * Only does anything when the phone's sound is actually being sent.
     */
    private fun applyOutput(mode: String) {
        val m = if (mode == "pc" || mode == "phone") mode else "both"
        output = m
        CastState.update { this.output = m }
        if (!audioWanted) return
        if (m == "pc") PhoneVolume.mute(this) else PhoneVolume.restore(this)
        sendOutputToPc()
    }

    /** The PC's receiver page already understands audio-output (the same message the PC app sends to a Fire TV). */
    private fun sendOutputToPc() {
        if (audioWanted) send(JSONObject().put("type", "audio-output").put("muted", output == "phone").put("volume", 1.0))
    }

    // ---------------- network ----------------

    /**
     * The PC is on the Wi-Fi, so keep every connection of this app (signaling and the WebRTC media) on the Wi-Fi network.
     * Without this, Android can switch its "default network" (e.g. to mobile data when the Wi-Fi has no internet, or when
     * connectivity is re-checked) and abort the cast's connections with "Software caused connection abort".
     * [then] runs once the Wi-Fi is pinned, or after 1.5 s if there is no Wi-Fi to pin (e.g. a wired or hotspot connection).
     */
    private fun pinToWifi(then: () -> Unit) {
        var done = false
        val proceed = { if (!done) { done = true; then() } }
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // also runs again if the phone moves to another Wi-Fi: follow it
                    val ok = try { cm.bindProcessToNetwork(network) } catch (e: Exception) { Log.w(TAG, "bind failed", e); false }
                    pinnedToWifi = ok
                    Log.i(TAG, "pinned to Wi-Fi network $network: $ok")
                    ui.post { proceed() }
                }
            }
            wifiCallback = cb
            cm.registerNetworkCallback(request, cb)
            ui.postDelayed({ proceed() }, 1500)
        } catch (e: Exception) {
            Log.w(TAG, "could not pin to Wi-Fi", e)
            proceed()
        }
    }

    private fun unpinWifi() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try { wifiCallback?.let { cm.unregisterNetworkCallback(it) } } catch (_: Exception) {}
        wifiCallback = null
        if (pinnedToWifi) { try { cm.bindProcessToNetwork(null) } catch (_: Exception) {}; pinnedToWifi = false }
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
        if (Build.VERSION.SDK_INT >= 29) {
            // Android 11+: recording audio from a service also needs the "microphone" service type (only when we hold the permission)
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (audioWanted && Build.VERSION.SDK_INT >= 30) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIF_ID, n, types)
        } else startForeground(NOTIF_ID, n)
    }

    // ---------------- signaling ----------------

    private fun send(o: JSONObject) { ws?.send(o.toString()) }

    private fun connect() {
        val socket = http.newWebSocket(Request.Builder().url("ws://$addr").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ui.post { if (!stopped) send(JSONObject().put("type", "join").put("code", code)) }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
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
            val fb = PeerConnectionFactory.builder()
            if (audioWanted) {
                // WebRTC records the microphone in 10 ms pieces; PlaybackAudio swaps each piece for the phone's own sound.
                val pb = PlaybackAudio().also { playback = it }
                adm = JavaAudioDeviceModule.builder(applicationContext)
                    .setSampleRate(PlaybackAudio.SAMPLE_RATE).setInputSampleRate(PlaybackAudio.SAMPLE_RATE)
                    .setUseStereoInput(false)               // mono: this library only hands over one channel per 10 ms, so mix L+R into it
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false)
                    .createAudioDeviceModule()
                fb.setAudioDeviceModule(adm)
                // the hook that swaps each 10 ms of microphone audio for the phone's own sound
                val apf = ExternalAudioProcessingFactory().also { audioProcessing = it }
                apf.setCapturePostProcessing(pb)
                fb.setAudioProcessingFactory(apf)
            }
            val f = fb
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
            if (audioWanted) {
                val projection = cap.mediaProjection
                val ok = projection != null && playback?.start(projection) == true
                if (ok) {
                    // music, not a phone call: switch off the voice processing (echo cancel, noise suppression, auto gain)
                    val c = MediaConstraints()
                    for (k in listOf("googEchoCancellation", "googAutoGainControl", "googNoiseSuppression", "googHighpassFilter"))
                        c.mandatory.add(MediaConstraints.KeyValuePair(k, "false"))
                    val asrc = f.createAudioSource(c).also { audioSource = it }
                    audioTrack = f.createAudioTrack("audio", asrc)
                } else { audioWanted = false; audioStatus = "no sound (could not capture)" }
            }
            CastState.update { audioOn = audioTrack != null }
            watchRotation()
            createPeer(f)
            applyOutput(output)
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
        audioTrack?.let { at ->
            val aSender = p.addTrack(at, listOf("cast"))
            try {
                val ap = aSender.parameters
                for (e in ap.encodings) e.maxBitrateBps = 128_000
                aSender.setParameters(ap)
            } catch (e: Throwable) { Log.w(TAG, "audio parameters not applied", e) }
        }

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
            override fun onCreateSuccess(created: SessionDescription) {
                val d = SessionDescription(created.type, if (audioTrack != null) stereoOpus(created.description) else created.description)
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

    /** Opus defaults to ~32 kbps and effectively mono. Ask for stereo at music quality (same as the PC app does). */
    private fun stereoOpus(sdp: String): String {
        val m = Regex("a=rtpmap:(\\d+) opus/48000/2").find(sdp) ?: return sdp
        val id = m.groupValues[1]
        return sdp.replace(Regex("(a=fmtp:$id )([^\\r\\n]*)")) { r ->
            r.groupValues[1] + r.groupValues[2].replace(Regex(";?(stereo|sprop-stereo|maxaveragebitrate|cbr)=\\d+"), "") +
                ";stereo=1;sprop-stereo=1;maxaveragebitrate=128000;cbr=0"
        }
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
                    PeerConnection.PeerConnectionState.CONNECTED -> { CastState.update { phase = CastState.Phase.CASTING }; sendOutputToPc() }
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
                        // diagnostics: what the audio send path sees (level 0..1 of the sound going into the encoder)
                        if (s.type == "media-source" && s.members["kind"] == "audio" && ((s.members["audioLevel"] as? Number)?.toDouble() ?: 0.0) > 0.001)
                            Log.d(TAG, "audio source level=${s.members["audioLevel"]}")
                        if (s.type != "outbound-rtp" || s.members["kind"] != "video") continue
                        val bytes = (s.members["bytesSent"] as? Number)?.toLong() ?: 0L
                        val now = System.currentTimeMillis()
                        val kbps = if (lastStatsAt > 0 && now > lastStatsAt) (bytes - lastBytes) * 8 / (now - lastStatsAt) else 0
                        lastBytes = bytes; lastStatsAt = now
                        val enc = (s.members["encoderImplementation"] ?: "?").toString()
                        val line = "${s.members["frameWidth"] ?: 0}×${s.members["frameHeight"] ?: 0} · " +
                            "${(s.members["framesPerSecond"] as? Number)?.toInt() ?: 0} fps · " +
                            (if (kbps >= 1000) "%.1f Mbps".format(kbps / 1000.0) else "$kbps kbps") + " · $enc" +
                            (if (audioStatus.isNotEmpty()) " · $audioStatus" else "")
                        CastState.update { detail = line }
                    }
                }
            }
            ui.postDelayed(this, 2000)
        }
    }

    // ---------------- shutdown ----------------

    /**
     * Ends the cast. The screen is told at once; the heavy clean-up (screen capture, audio recorder, WebRTC engine) runs on a
     * background thread, because releasing them can take seconds and would freeze the app if done on the main thread.
     */
    private fun stopCasting(reason: String) {
        if (stopped) return
        stopped = true
        Log.i(TAG, "stopCasting: $reason")
        ui.removeCallbacksAndMessages(null)
        CastState.update { phase = CastState.Phase.IDLE; detail = ""; message = reason; audioOn = false }
        try { ws?.send("""{"type":"bye"}"""); ws?.close(1000, null) } catch (_: Exception) {}
        ws = null
        try { displayListener?.let { (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(it) } } catch (_: Exception) {}
        displayListener = null
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
        unpinWifi()
        PhoneVolume.restore(this)
        pendingIce.clear(); remoteSet = false; lastBytes = 0; lastStatsAt = 0

        // take the resources away from this object, then release them elsewhere
        val cap = capturer; val pb = playback; val at = audioTrack; val asrc = audioSource; val vt = track; val vs = source
        val st = helper; val peer = pc; val f = factory; val dev = adm; val apf = audioProcessing; val gl = egl
        capturer = null; playback = null; audioTrack = null; audioSource = null; track = null; source = null
        helper = null; pc = null; factory = null; adm = null; audioProcessing = null; egl = null

        Thread({
            val t0 = System.currentTimeMillis()
            try { cap?.stopCapture() } catch (_: Exception) {}
            try { cap?.dispose() } catch (_: Exception) {}
            try { pb?.stop() } catch (_: Exception) {}
            try { at?.dispose() } catch (_: Exception) {}
            try { asrc?.dispose() } catch (_: Exception) {}
            try { vt?.dispose() } catch (_: Exception) {}
            try { vs?.dispose() } catch (_: Exception) {}
            try { st?.dispose() } catch (_: Exception) {}
            try { peer?.close(); peer?.dispose() } catch (_: Exception) {}
            try { f?.dispose() } catch (_: Exception) {}
            try { dev?.release() } catch (_: Exception) {}
            // (not apf.destroy(): the PeerConnectionFactory already frees that native object when it is disposed; calling it again crashes)
            try { gl?.release() } catch (_: Exception) {}
            Log.i(TAG, "resources released in ${System.currentTimeMillis() - t0} ms")
            ui.post {
                if (!stopped) return@post                      // a new cast has started meanwhile: leave the service running
                if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
                stopSelf()
            }
        }, "CastRelease").start()
    }

    override fun onDestroy() {
        stopCasting("Stopped")
        http.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
