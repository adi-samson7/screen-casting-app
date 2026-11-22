package com.example.cast.core

import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/** SdpObserver with no-op defaults, so callers only override what they need. */
open class SdpAdapter : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}
