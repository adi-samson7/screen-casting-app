package com.example.castsender

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.ThreadUtils
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * Screen capturer for WebRTC, built on MediaProjection.
 *
 * Why not the library's ScreenCapturerAndroid? Its changeCaptureFormat() (used when the phone rotates) recreates the virtual
 * display from the same MediaProjection, and Android 14+ forbids that ("Don't take multiple captures by invoking
 * createVirtualDisplay multiple times on the same instance") and ends the cast. This class creates the virtual display once and
 * resizes it in place with VirtualDisplay.resize(), which is allowed.
 */
class ProjectionCapturer(
    private val permissionData: Intent,
    private val onProjectionStopped: () -> Unit,
) : VideoCapturer, VideoSink {
    private companion object { const val DPI = 400 }

    private var helper: SurfaceTextureHelper? = null
    private var context: Context? = null
    private var observer: CapturerObserver? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null

    /** The active screen-capture session (needed to capture the phone's audio too). */
    val mediaProjection: MediaProjection? get() = projection

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { onProjectionStopped() }        // the user ended sharing from the system UI
    }

    override fun initialize(surfaceTextureHelper: SurfaceTextureHelper, applicationContext: Context, capturerObserver: CapturerObserver) {
        helper = surfaceTextureHelper; context = applicationContext; observer = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        val h = helper ?: throw IllegalStateException("initialize() first")
        val mpm = context!!.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(Activity.RESULT_OK, permissionData) ?: throw IllegalStateException("Screen capture permission is no longer valid")
        projection = p
        p.registerCallback(callback, h.handler)                 // Android 14+: must be registered before creating the display
        h.setTextureSize(width, height)
        display = p.createVirtualDisplay("CastSender", width, height, DPI,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, Surface(h.surfaceTexture), null, null)
        observer?.onCapturerStarted(true)
        h.startListening(this)
    }

    /** The phone rotated (or the quality changed): change the capture size without recreating the display. */
    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        val h = helper ?: return
        h.handler.post {
            h.setTextureSize(width, height)
            try { display?.resize(width, height, DPI) } catch (_: Exception) {}
        }
    }

    override fun onFrame(frame: VideoFrame) { observer?.onFrameCaptured(frame) }

    override fun stopCapture() {
        val h = helper ?: return
        ThreadUtils.invokeAtFrontUninterruptibly(h.handler) {
            h.stopListening()
            display?.release(); display = null
            projection?.let { it.unregisterCallback(callback); it.stop() }
            projection = null
            observer?.onCapturerStopped()
        }
    }

    override fun dispose() {}
    override fun isScreencast() = true
}
