package com.example.castsender

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * Silences / restores the phone's own speaker (the media volume) so a cast can play its sound on the PC only.
 *
 * The volume we found is written to disk BEFORE it is changed, so it can be put back even if the app is killed in the
 * meantime (the app also calls [restore] whenever it opens with no cast running). If you changed the volume yourself
 * while muted, [restore] leaves your choice alone and only forgets the saved value.
 */
object PhoneVolume {
    private const val PREFS = "cast_sender_prefs"
    private const val KEY = "restoreVolume"
    private const val TAG = "PhoneVolume"

    private fun am(ctx: Context) = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Turns the phone's media volume to 0 (remembering the old level). */
    fun mute(ctx: Context) {
        try {
            val audio = am(ctx)
            val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (cur > 0) {
                prefs(ctx).edit().putInt(KEY, cur).commit()                 // commit(): must be on disk before we change anything
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)       // flags 0: no volume bar pops up
                Log.i(TAG, "phone media volume $cur -> 0")
            }
        } catch (e: Exception) { Log.w(TAG, "could not mute the phone", e) }
    }

    /** Puts the phone's media volume back, if it is still the muted 0 we set. */
    fun restore(ctx: Context) {
        try {
            val saved = prefs(ctx).getInt(KEY, -1)
            if (saved < 0) return
            val audio = am(ctx)
            if (saved > 0 && audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0)
                Log.i(TAG, "phone media volume restored to $saved")
            }
            prefs(ctx).edit().remove(KEY).commit()
        } catch (e: Exception) { Log.w(TAG, "could not restore the phone volume", e) }
    }
}
