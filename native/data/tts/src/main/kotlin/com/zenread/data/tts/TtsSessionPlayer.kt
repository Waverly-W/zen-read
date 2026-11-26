package com.zenread.data.tts

import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The [Player] the media session sees: a facade over [TtsPlaybackController]. It reports one
 * media item (the book, with the chapter as subtitle) and maps transport commands from the
 * notification, lock screen and headset onto the controller: next/previous move by sentence.
 */
@OptIn(UnstableApi::class)
internal class TtsSessionPlayer(private val controller: TtsPlaybackController) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var snapshot = controller.state.value
    private val job: Job = CoroutineScope(Dispatchers.Main.immediate).launch {
        controller.state.collect {
            snapshot = it
            invalidateState()
        }
    }

    override fun getState(): State {
        val s = snapshot
        val builder = State.Builder().setAvailableCommands(COMMANDS)
        val book = s.book
        if (!s.active || book == null) {
            return builder
                .setPlaybackState(STATE_IDLE)
                .setPlayWhenReady(false, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .build()
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(book.title)
            .setArtist(s.chapter ?: book.author)
            .setAlbumTitle(book.title)
            .setDisplayTitle(book.title)
            .setSubtitle(s.chapter ?: book.author)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
            .apply { book.coverPath?.let { path -> setArtworkUri(Uri.fromFile(File(path))) } }
            .build()
        val item = MediaItemData.Builder(book.bookId)
            .setMediaItem(MediaItem.Builder().setMediaId(book.bookId.toString()).setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setIsSeekable(false)
            .build()
        return builder
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(if (s.status == TtsStatus.PREPARING) STATE_BUFFERING else STATE_READY)
            .setPlayWhenReady(s.playing, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackParameters(PlaybackParameters(s.rate))
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) controller.play() else controller.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        controller.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_FORWARD -> controller.next()
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, COMMAND_SEEK_BACK -> controller.previous()
            else -> Unit
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        controller.setRate(playbackParameters.speed)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        job.cancel()
        return Futures.immediateVoidFuture()
    }

    private companion object {
        val COMMANDS = Player.Commands.Builder()
            .addAll(
                COMMAND_PLAY_PAUSE,
                COMMAND_STOP,
                COMMAND_SEEK_TO_NEXT,
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_SET_SPEED_AND_PITCH,
                COMMAND_GET_CURRENT_MEDIA_ITEM,
                COMMAND_GET_TIMELINE,
                COMMAND_GET_METADATA,
                COMMAND_RELEASE,
            )
            .build()
    }
}
