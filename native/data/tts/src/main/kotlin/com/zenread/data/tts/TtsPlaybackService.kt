package com.zenread.data.tts

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Hosts the media session for read-aloud. Media3 posts the playback notification and promotes
 * this service to the foreground while playing, which keeps the process (and with it the
 * reader's WebView, the text source) alive with the screen off. Lock-screen and headset
 * controls arrive through the session and are mapped by [TtsSessionPlayer].
 *
 * Started by [TtsPlaybackController.start]; stops itself when the session ends.
 */
@AndroidEntryPoint
class TtsPlaybackService : MediaSessionService() {
    @Inject
    lateinit var controller: TtsPlaybackController

    private var session: MediaSession? = null
    private var player: TtsSessionPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val sessionPlayer = TtsSessionPlayer(controller).also { player = it }
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val built = MediaSession.Builder(this, sessionPlayer)
            .setId(SESSION_ID)
            .apply {
                if (launch != null) {
                    setSessionActivity(
                        PendingIntent.getActivity(this@TtsPlaybackService, 0, launch, PendingIntent.FLAG_IMMUTABLE),
                    )
                }
            }
            .build()
        session = built
        // Without a connected MediaController the service must register the session itself
        // for Media3 to manage its notification.
        addSession(built)
        scope.launch {
            // Leave once the session has ended (stop, end of book, reader closed). The grace
            // period covers start(), which briefly passes through IDLE when replacing a session.
            controller.state.map { it.active }.distinctUntilChanged().collectLatest { active ->
                if (!active) {
                    delay(STOP_GRACE_MS)
                    stopSelf()
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The text comes from the reader's WebView, which dies with the task.
        controller.stop()
        stopSelf()
    }

    @OptIn(UnstableApi::class)
    override fun onDestroy() {
        scope.cancel()
        session?.let {
            removeSession(it)
            it.release()
        }
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private companion object {
        const val SESSION_ID = "zenread-read-aloud"
        const val STOP_GRACE_MS = 1_500L
    }
}
