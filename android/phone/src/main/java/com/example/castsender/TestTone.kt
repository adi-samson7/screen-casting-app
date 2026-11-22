package com.example.castsender

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * A short two-note chime played through the phone's normal media output.
 * While casting with sound on it lets you check that the PC really hears the phone, without needing another app.
 * (This app allows its own sound to be captured, see allowAudioPlaybackCapture in the manifest.)
 */
object TestTone {
    fun play() {
        Thread {
            try {
                val rate = 48_000
                val frames = (rate * 1.4).toInt()
                val pcm = ShortArray(frames * 2)                                  // stereo
                for (i in 0 until frames) {
                    val t = i.toDouble() / rate
                    val freq = if (t < 0.7) 523.25 else 783.99                    // C5 then G5
                    val local = if (t < 0.7) t else t - 0.7
                    val env = min(1.0, local * 40) * min(1.0, (0.7 - local) * 12)   // quick fade in / out of each note
                    val v = (sin(2 * PI * freq * t) * 0.5 * env * 32767).toInt().toShort()
                    pcm[i * 2] = v; pcm[i * 2 + 1] = v
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(1700)
                track.release()
            } catch (_: Exception) {}
        }.start()
    }
}
