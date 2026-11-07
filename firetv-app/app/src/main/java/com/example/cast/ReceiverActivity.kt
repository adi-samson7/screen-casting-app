package com.example.cast

import android.app.Activity
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.TimeUnit

/**
 * Native receiver: same signaling protocol as receiver.html, but video goes through the
 * MediaCodec hardware decoder straight onto a SurfaceView (no browser in the way).
 */
class ReceiverActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()

    private lateinit var addr: String
    private lateinit var egl: EglBase
    private lateinit var factory: PeerConnectionFactory
    private lateinit var renderer: SurfaceViewRenderer
    private lateinit var idle: LinearLayout
    private lateinit var codeView: TextView
    private lateinit var statusView: TextView
    private lateinit var headerView: TextView
    private lateinit var hintView: TextView
    private lateinit var pcLabel: String      // the PC's name, shown so it's clear which PC this TV is paired with

    /** This TV's name, shown on the PC ("Casting to …"). */
    private fun tvName(): String =
        (try { android.provider.Settings.Global.getString(contentResolver, "device_name") } catch (_: Exception) { null })
            ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL ?: "Fire TV"

    private var ws: WebSocket? = null
    private var pc: PeerConnection? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private val pendingIce = ArrayList<IceCandidate>()
    private var remoteSet = false

    private var tvMuted = false          // controlled from the PC's sound panel; default: sound on
    private var tvVolume = 1.0
    private var failures = 0
    private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        addr = intent.getStringExtra(MainActivity.EXTRA_ADDR) ?: run { finish(); return }
        pcLabel = intent.getStringExtra(MainActivity.EXTRA_NAME) ?: addr

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions()
        )
        egl = EglBase.create()

        // Play as normal MEDIA (movie/music). WebRTC's default is "voice call" audio, which makes Bluetooth
        // switch to its low-quality hands-free profile (mono, 8-16 kHz) = tinny audio.
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = false }
        val adm = JavaAudioDeviceModule.builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setUseStereoOutput(true)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()

        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .createPeerConnectionFactory()

        buildUi()
        connect()
        ui.postDelayed(statsTick, 2000)
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        renderer = SurfaceViewRenderer(this).apply {
            init(egl.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
            visibility = View.INVISIBLE
        }
        root.addView(renderer, FrameLayout.LayoutParams(-1, -1))

        idle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        headerView = TextView(this).apply {
            text = "Connected to $pcLabel"
            textSize = 34f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        idle.addView(headerView)
        hintView = TextView(this).apply {
            text = "On $pcLabel open Cast to Fire TV and enter this code"
            textSize = 24f; setTextColor(Color.parseColor("#99AAAA")); gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
        }
        idle.addView(hintView)
        codeView = TextView(this).apply {
            text = "------"; textSize = 110f; setTextColor(Color.WHITE); letterSpacing = 0.2f
            setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER
        }
        idle.addView(codeView)
        idle.addView(TextView(this).apply {
            text = addr; textSize = 20f; setTextColor(Color.parseColor("#667788")); gravity = Gravity.CENTER
        })
        root.addView(idle, FrameLayout.LayoutParams(-1, -1))

        statusView = TextView(this).apply { textSize = 16f; setTextColor(Color.parseColor("#667788")); gravity = Gravity.END }
        root.addView(statusView, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 32, 20) })
        setContentView(root)
        setStatus("connecting…")
    }

    private fun setStatus(t: String) { statusView.text = t }

    // ---------------- signaling ----------------

    private fun send(o: JSONObject) { ws?.send(o.toString()) }

    private fun connect() {
        if (closed) return
        val socket = http.newWebSocket(Request.Builder().url("ws://$addr").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ui.post {
                    if (webSocket !== ws) return@post
                    failures = 0
                    setStatus("ready")
                    send(JSONObject().put("type", "register").put("name", tvName()))
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                ui.post { if (webSocket === ws) handle(JSONObject(text)) }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { dropped(webSocket) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { dropped(webSocket) }
        })
        ws = socket
    }

    private fun dropped(webSocket: WebSocket) {
        ui.post {
            if (webSocket !== ws || closed) return@post
            ws = null
            resetPeer()
            failures++
            if (failures >= 6) {            // PC probably moved or restarted: go back and rediscover
                finish()
                return@post
            }
            setStatus("reconnecting…")
            ui.postDelayed({ connect() }, 2000)
        }
    }

    private fun handle(m: JSONObject) {
        when (m.optString("type")) {
            "registered" -> {
                codeView.text = m.getString("code")
                val pcName = m.optString("pcName", pcLabel)
                headerView.text = "Connected to $pcName"
                hintView.text = "On $pcName open Cast to Fire TV and enter this code"
            }
            "sender-joined" -> setStatus("sender connected")
            "offer" -> handleOffer(m.getJSONObject("offer"))
            "candidate" -> addRemoteCandidate(m.getJSONObject("candidate"))
            "audio-output" -> {
                tvMuted = m.optBoolean("muted", false)
                tvVolume = m.optDouble("volume", 1.0).coerceIn(0.0, 1.0)
                applyAudio()
            }
            "bye" -> resetPeer()
        }
    }

    // ---------------- WebRTC ----------------

    private fun handleOffer(offer: JSONObject) {
        resetPeer()
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN   // LAN only, no STUN/TURN
        }
        val p = factory.createPeerConnection(config, observer) ?: return
        pc = p
        val desc = SessionDescription(SessionDescription.Type.OFFER, offer.getString("sdp"))
        p.setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() {
                ui.post {
                    if (pc !== p) return@post
                    remoteSet = true
                    pendingIce.forEach { p.addIceCandidate(it) }
                    pendingIce.clear()
                    p.createAnswer(object : SdpAdapter() {
                        override fun onCreateSuccess(created: SessionDescription) {
                            // Accept stereo Opus (music quality) in the answer.
                            val sdp = created.description.replace(
                                Regex("(a=fmtp:\\d+ [^\\r\\n]*useinbandfec=1)"), "$1;stereo=1;sprop-stereo=1")
                            val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
                            p.setLocalDescription(object : SdpAdapter() {
                                override fun onSetSuccess() {
                                    ui.post {
                                        send(JSONObject().put("type", "answer").put("answer",
                                            JSONObject().put("type", "answer").put("sdp", answer.description)))
                                    }
                                }
                            }, answer)
                        }
                    }, MediaConstraints())
                }
            }
        }, desc)
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
        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            ui.post {
                when (track) {
                    is VideoTrack -> {
                        videoTrack = track
                        track.addSink(renderer)
                        renderer.visibility = View.VISIBLE
                        idle.visibility = View.GONE
                        setStatus("")
                    }
                    is AudioTrack -> { audioTrack = track; applyAudio() }
                }
            }
        }
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
            ui.post {
                when (s) {
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.CLOSED -> resetPeer()
                    PeerConnection.PeerConnectionState.CONNECTED -> setStatus("")
                    else -> {}
                }
            }
        }
        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onAddStream(s: MediaStream) {}
        override fun onRemoveStream(s: MediaStream) {}
        override fun onDataChannel(d: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
    }

    private fun applyAudio() {
        audioTrack?.setEnabled(!tvMuted)
        audioTrack?.setVolume(tvVolume)              // WebRTC volume is a gain: 1.0 = unchanged (>1 amplifies and distorts)
        send(JSONObject().put("type", "audio-state")
            .put("volume", Math.round(tvVolume * 100).toInt())
            .put("unlocked", true).put("muted", tvMuted)
            .put("audioTracks", if (audioTrack != null) 1 else 0).put("trackLive", audioTrack != null))
    }

    // ---------------- stats overlay (OK/Select button toggles it) ----------------

    private var showStats = true
    private val statsTick = object : Runnable {
        override fun run() {
            val p = pc
            if (p != null && showStats && videoTrack != null) {
                p.getStats { report ->
                    var line = ""
                    var audio = ""
                    for (s in report.statsMap.values) {
                        if (s.type == "inbound-rtp" && s.members["kind"] == "audio") {
                            val total = (s.members["totalSamplesReceived"] as? Number)?.toDouble() ?: 0.0
                            val concealed = (s.members["concealedSamples"] as? Number)?.toDouble() ?: 0.0
                            val jitterMs = ((s.members["jitter"] as? Number)?.toDouble() ?: 0.0) * 1000
                            audio = "\naudio: concealed ${if (total > 0) Math.round(concealed * 100 / total) else 0}% · " +
                                "jitter ${Math.round(jitterMs)} ms · lost ${s.members["packetsLost"] ?: 0}"
                        }
                        if (s.type == "inbound-rtp" && s.members["kind"] == "video") {
                            val codec = (report.statsMap[s.members["codecId"]]?.members?.get("mimeType") ?: "?").toString().removePrefix("video/")
                            line = "$codec · ${s.members["decoderImplementation"] ?: "?"} · " +
                                "${s.members["frameWidth"] ?: 0}x${s.members["frameHeight"] ?: 0} · " +
                                "${(s.members["framesPerSecond"] as? Number)?.toInt() ?: 0} fps · " +
                                "lost ${s.members["packetsLost"] ?: 0} · dropped ${s.members["framesDropped"] ?: 0}"
                        }
                    }
                    ui.post { if (videoTrack != null && showStats) statusView.text = line + audio }
                }
            } else if (!showStats && videoTrack != null) statusView.text = ""
            ui.postDelayed(this, 2000)
        }
    }

    private fun resetPeer() {
        videoTrack?.removeSink(renderer)
        videoTrack = null; audioTrack = null
        pendingIce.clear(); remoteSet = false
        pc?.let { it.close(); it.dispose() }
        pc = null
        if (!closed) {
            renderer.clearImage()
            renderer.visibility = View.INVISIBLE
            idle.visibility = View.VISIBLE
            if (ws != null) setStatus("ready")
        }
    }

    // ---------------- lifecycle ----------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            showStats = !showStats
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        closed = true
        ui.removeCallbacksAndMessages(null)
        ws?.close(1000, null); ws = null
        resetPeer()
        renderer.release()
        factory.dispose()
        egl.release()
        http.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}

/** SdpObserver with no-op defaults. */
open class SdpAdapter : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}
