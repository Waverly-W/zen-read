package com.zenread.data.tts

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Plays one synthesized clip at a time with an internal ExoPlayer. Audio focus and the media
 * session are handled by [TtsPlaybackController]; this player is never exposed to the session.
 * Main thread only.
 */
internal class ClipPlayer(private val context: Context) {
    private var exo: ExoPlayer? = null

    @OptIn(UnstableApi::class)
    private fun player(): ExoPlayer = exo ?: ExoPlayer.Builder(context.applicationContext)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            /* handleAudioFocus = */ false,
        )
        // Keeps the CPU and Wi-Fi awake while a clip plays with the screen off.
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()
        .also { exo = it }

    /** Fraction of the current clip already played, or null before its duration is known. */
    val fraction: Float?
        get() {
            val player = exo ?: return null
            val duration = player.duration
            if (duration == C.TIME_UNSET || duration <= 0) return null
            return (player.currentPosition.toFloat() / duration).coerceIn(0f, 0.999f)
        }

    @OptIn(UnstableApi::class)
    suspend fun play(audio: ByteArray) {
        val player = player()
        suspendCancellableCoroutine { continuation ->
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        player.removeListener(this)
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    player.removeListener(this)
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            TtsException(TtsException.Reason.INVALID_AUDIO, "Audio playback failed (${error.errorCodeName})"),
                        )
                    }
                }
            }
            continuation.invokeOnCancellation {
                // Cancellation may arrive from another thread; ExoPlayer is main-thread only.
                android.os.Handler(player.applicationLooper).post {
                    player.removeListener(listener)
                    player.stop()
                }
            }
            player.addListener(listener)
            val source = ProgressiveMediaSource.Factory { ByteArrayDataSource(audio) }
                .createMediaSource(MediaItem.fromUri("zenread-tts://clip".toUri()))
            player.setMediaSource(source)
            player.prepare()
            player.playWhenReady = true
        }
    }

    fun pause() {
        exo?.playWhenReady = false
    }

    fun resume() {
        exo?.playWhenReady = true
    }

    fun stop() {
        exo?.stop()
    }

    fun release() {
        exo?.release()
        exo = null
    }
}
