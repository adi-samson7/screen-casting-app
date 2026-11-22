package com.example.castsender

import android.os.Handler
import android.os.Looper

/** What the cast service is doing, shared with the screen. Changes are delivered on the main thread. */
object CastState {
    enum class Phase { IDLE, CONNECTING, CASTING }

    @Volatile var phase = Phase.IDLE
    @Volatile var target = ""          // name of the PC being cast to
    @Volatile var detail = ""          // live line: "864×1920 · 30 fps · 5.8 Mbps · H264 · MediaCodec"
    @Volatile var message = ""         // why the last cast ended (shown on the home screen)
    @Volatile var audioOn = false      // the phone's sound is being sent
    @Volatile var output = "both"      // where the sound plays: "phone", "pc" or "both"

    /** Set by the visible screen; called on the main thread whenever something changed. */
    var listener: (() -> Unit)? = null

    private val ui = Handler(Looper.getMainLooper())
    fun update(change: CastState.() -> Unit) = ui.post { change(this); listener?.invoke() }
}
