package com.zenread.data.tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat

/** Focus changes the controller reacts to. */
internal enum class FocusChange { LOSS, LOSS_TRANSIENT, GAIN }

/**
 * Android plumbing around playback: audio focus, "becoming noisy" (headphones unplugged) and a
 * partial wake lock so the reader's JavaScript and the network keep running between sentences
 * while the screen is off.
 */
internal class PlaybackEnvironment(
    context: Context,
    private val onFocusChange: (FocusChange) -> Unit,
    private val onNoisy: () -> Unit,
) {
    private val app = context.applicationContext
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        // Speech must not be lost under a notification sound: pause instead of ducking.
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> onFocusChange(FocusChange.GAIN)
                AudioManager.AUDIOFOCUS_LOSS -> onFocusChange(FocusChange.LOSS)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                -> onFocusChange(FocusChange.LOSS_TRANSIENT)
            }
        }, main)
        .build()

    private var hasFocus = false

    fun requestFocus(): Boolean {
        if (hasFocus) return true
        hasFocus = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    fun abandonFocus() {
        if (!hasFocus) return
        audioManager.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onNoisy()
        }
    }
    private var noisyRegistered = false

    private val wakeLock = app.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZenRead:ReadAloud")
        .apply { setReferenceCounted(false) }

    /** Called before each sentence while playing; the timeout covers a stuck engine. */
    fun onPlaying() {
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        if (!noisyRegistered) {
            ContextCompat.registerReceiver(
                app,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyRegistered = true
        }
    }

    fun onNotPlaying() {
        if (wakeLock.isHeld) wakeLock.release()
        if (noisyRegistered) {
            app.unregisterReceiver(noisyReceiver)
            noisyRegistered = false
        }
    }

    private companion object {
        const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
