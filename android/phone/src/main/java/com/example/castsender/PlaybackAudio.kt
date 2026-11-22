package com.example.castsender

import android.annotation.TargetApi
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import org.webrtc.ExternalAudioProcessingFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Captures what the phone is playing (music, videos, games: Android's "playback capture", Android 10+) and feeds it to WebRTC.
 *
 * How it fits together: WebRTC keeps "recording" the microphone and runs every 10 ms of it through its audio processing.
 * This class plugs into that processing step ([process], via ExternalAudioProcessingFactory) and overwrites each 10 ms block
 * with the phone's own playback audio, so what is encoded and sent is the phone's sound, not the microphone.
 * A small ring buffer decouples the two recorders (they run on separate clocks).
 *
 * Audio is captured at 48 kHz, 16-bit stereo and sent as mono (left and right mixed). Some apps (streaming apps, some games) forbid capture: their sound arrives as silence.
 */
@TargetApi(29)
class PlaybackAudio : ExternalAudioProcessingFactory.AudioProcessing {
    companion object {
        const val SAMPLE_RATE = 48_000
        private const val TAG = "PlaybackAudio"
        private const val BYTES_PER_FRAME = 4                         // 16-bit stereo
        private const val RING_BYTES = SAMPLE_RATE * BYTES_PER_FRAME / 5     // 200 ms: more than that is dropped (keeps delay low)
    }

    private val lock = Any()
    private val ring = ByteArray(RING_BYTES)
    private var readPos = 0
    private var size = 0
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var channels = 2
    @Volatile private var rate = SAMPLE_RATE

    // diagnostics, logged every 2 s: where along the path does the sound get lost?
    private var capChunks = 0; private var capPeak = 0                          // what Android's playback capture delivered
    private var cbCalls = 0; private var cbPeak = 0; private var cbSilent = 0   // what we handed to WebRTC
    private var lastLog = 0L
    private var loggedShape = false
    private var lastSummary = 0L

    /** True once the phone's audio is being captured. */
    val active get() = running

    fun start(projection: MediaProjection): Boolean {
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf, RING_BYTES))
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); Log.w(TAG, "AudioRecord not initialized"); return false }
            record = rec
            running = true
            rec.startRecording()
            thread = Thread({
                val chunk = ByteArray(1920)                                  // 10 ms
                while (running) {
                    val n = try { rec.read(chunk, 0, chunk.size) } catch (_: Exception) { -1 }
                    if (n <= 0) { if (running) Thread.sleep(5); continue }
                    capChunks++; capPeak = maxOf(capPeak, peak(chunk, n))
                    write(chunk, n)
                    val now = System.currentTimeMillis()
                    if (now - lastLog > 2000) {
                        lastLog = now
                        if (now - lastSummary > 10_000) { lastSummary = now; Log.d(TAG, "capture: chunks=$capChunks peak=$capPeak | to WebRTC: calls=$cbCalls peak=$cbPeak silentFrames=$cbSilent") }
                        capPeak = 0; cbPeak = 0
                    }
                }
            }, "PlaybackCapture").also { it.start() }
            Log.i(TAG, "playback capture started")
            true
        } catch (e: Exception) {
            Log.w(TAG, "playback capture could not start", e)
            running = false
            false
        }
    }

    fun stop() {
        running = false
        try { thread?.join(300) } catch (_: Exception) {}
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null; thread = null
        synchronized(lock) { readPos = 0; size = 0 }
    }

    private fun write(data: ByteArray, n: Int) {
        synchronized(lock) {
            for (i in 0 until n) {
                if (size == RING_BYTES) { readPos = (readPos + 1) % RING_BYTES; size-- }   // full: drop the oldest byte
                ring[(readPos + size) % RING_BYTES] = data[i]
                size++
            }
        }
    }

    // ---- WebRTC audio processing hooks (ExternalAudioProcessingFactory.AudioProcessing) ----

    override fun initialize(sampleRateHz: Int, numChannels: Int) {
        rate = sampleRateHz; channels = numChannels
        Log.i(TAG, "WebRTC audio processing: $sampleRateHz Hz, $numChannels channel(s)")
    }

    override fun reset(newRate: Int) { rate = newRate; Log.i(TAG, "WebRTC audio processing reset: $newRate Hz") }

    /**
     * WebRTC hands us 10 ms of the outgoing audio (floats in the 16-bit range, one block per channel) just before it is
     * encoded. Replace it with the next 10 ms of the phone's playback audio (or silence if there is none yet).
     */
    override fun process(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
        cbCalls++
        val fb = buffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        if (!loggedShape) {
            loggedShape = true
            Log.i(TAG, "process(): bands=$numBands frames=$numFrames capacityFloats=${fb.capacity()} channels=$channels rate=$rate")
        }
        val ch = channels.coerceAtLeast(1)
        var p = 0f
        synchronized(lock) {
            for (f in 0 until numFrames) {
                var l = 0f; var r = 0f
                if (rate == SAMPLE_RATE && size >= BYTES_PER_FRAME) {
                    l = sample(readPos).toFloat(); r = sample((readPos + 2) % RING_BYTES).toFloat()
                    readPos = (readPos + BYTES_PER_FRAME) % RING_BYTES; size -= BYTES_PER_FRAME
                }
                p = maxOf(p, Math.abs(l), Math.abs(r))
                // WebRTC gives us one channel (mono) of 10 ms: put the left+right mix there
                val mix = (l + r) / 2f
                if (f < fb.capacity()) fb.put(f, mix)
                if (ch >= 2 && numFrames + f < fb.capacity()) fb.put(numFrames + f, mix)      // in case a second channel region exists
            }
        }
        val pk = p.toInt(); cbPeak = maxOf(cbPeak, pk); if (pk == 0) cbSilent++
    }

    /** Loudest sample (0..32767) in the first [n] bytes of 16-bit little-endian audio. */
    private fun peak(b: ByteArray, n: Int): Int {
        var m = 0
        var i = 0
        while (i + 1 < n) { val v = Math.abs((b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)); if (v > m) m = v; i += 2 }
        return m
    }

    private fun sample(i: Int): Int = ((ring[i].toInt() and 0xFF) or (ring[(i + 1) % RING_BYTES].toInt() shl 8)).toShort().toInt()   // signed 16-bit, little-endian
}
