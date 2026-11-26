package com.vakarux.instadownload

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Plays short, distinct tones for download success and failure.
 *
 * Uses [ToneGenerator], which needs no bundled audio assets and honours the user's notification
 * volume. Tones are produced off the main thread and the generator is released shortly after so
 * nothing leaks when the app is killed.
 */
object DownloadSounds {

    private val scope = CoroutineScope(Dispatchers.Default)

    /** Cheerful two-step rising tone — a download finished. */
    fun playSuccess(context: Context, enabled: Boolean = true) {
        if (!enabled) return
        play(context, ToneGenerator.TONE_PROP_ACK)
    }

    /** Low, distinct buzz — a download failed. */
    fun playFailure(context: Context, enabled: Boolean = true) {
        if (!enabled) return
        play(context, ToneGenerator.TONE_SUP_ERROR)
    }

    private fun play(context: Context, tone: Int) {
        val appContext = context.applicationContext
        scope.launch {
            runCatching {
                // Keep notification volume as the user set it; never force the ringer up.
                @Suppress("DEPRECATION")
                val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
                try {
                    generator.startTone(tone, 200)
                    Thread.sleep(260)
                } finally {
                    generator.release()
                }
            }
        }
    }
}
