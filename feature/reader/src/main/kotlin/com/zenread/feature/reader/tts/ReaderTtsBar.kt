package com.zenread.feature.reader.tts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BedtimeOff
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zenread.core.designsystem.motion.ZenMotion
import com.zenread.data.tts.SleepTimerOption
import com.zenread.data.tts.TtsException
import com.zenread.data.tts.TtsPlaybackState
import com.zenread.data.tts.TtsStatus
import com.zenread.feature.reader.R
import java.util.Locale

/** Speeds offered in the player; settings allow any value in the slider's range. */
private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

/**
 * Compact read-aloud controls floating over the page while a session is active: previous /
 * next sentence, speed, sleep timer, stop, and play/pause as the toolbar's FAB.
 */
@Composable
internal fun ReaderTtsBar(
    visible: Boolean,
    state: TtsPlaybackState,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    onRate: (Float) -> Unit,
    onSleepTimer: (Int) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = ZenMotion.enterFromBottom,
        exit = ZenMotion.exitToBottom,
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.error?.let { error ->
                Surface(
                    onClick = onDismissError,
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.widthIn(max = 480.dp),
                ) {
                    Text(
                        text = errorText(error),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
            HorizontalFloatingToolbar(
                expanded = true,
                colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                floatingActionButton = {
                    FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = onTogglePlay) {
                        when (state.status) {
                            TtsStatus.PREPARING -> LoadingIndicator(Modifier.size(32.dp))
                            TtsStatus.PLAYING -> Icon(Icons.Rounded.Pause, stringResource(R.string.reader_tts_pause))
                            else -> Icon(Icons.Rounded.PlayArrow, stringResource(R.string.reader_tts_play))
                        }
                    }
                },
            ) {
                IconButton(onClick = onPrevious) {
                    Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.reader_tts_previous))
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Rounded.SkipNext, stringResource(R.string.reader_tts_next))
                }
                SpeedButton(rate = state.rate, onRate = onRate)
                SleepButton(
                    minutes = state.sleepTimerMinutes,
                    remainingMillis = state.sleepRemainingMillis,
                    onSleepTimer = onSleepTimer,
                )
                IconButton(onClick = onStop) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.reader_tts_stop))
                }
            }
        }
    }
}

@Composable
private fun SpeedButton(rate: Float, onRate: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        val label = stringResource(R.string.reader_tts_speed)
        TextButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "$label ${formatRate(rate)}" }) {
            Text(formatRate(rate), style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SPEEDS.forEach { speed ->
                DropdownMenuItem(
                    text = { Text(formatRate(speed)) },
                    onClick = {
                        open = false
                        onRate(speed)
                    },
                    trailingIcon = if (speed == rate) {
                        { Text("•", color = MaterialTheme.colorScheme.primary) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun SleepButton(minutes: Int, remainingMillis: Long?, onSleepTimer: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        val armed = minutes != SleepTimerOption.OFF
        if (armed && remainingMillis != null) {
            TextButton(onClick = { open = true }) {
                Text(formatRemaining(remainingMillis), style = MaterialTheme.typography.labelLarge)
            }
        } else {
            IconButton(onClick = { open = true }) {
                Icon(
                    if (armed) Icons.Rounded.Bedtime else Icons.Rounded.BedtimeOff,
                    stringResource(R.string.reader_tts_sleep_timer),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SleepTimerOption.choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(sleepLabel(choice)) },
                    onClick = {
                        open = false
                        onSleepTimer(choice)
                    },
                    trailingIcon = if (choice == minutes) {
                        { Text("•", color = MaterialTheme.colorScheme.primary) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
internal fun sleepLabel(minutes: Int): String = when (minutes) {
    SleepTimerOption.OFF -> stringResource(R.string.reader_tts_sleep_off)
    SleepTimerOption.END_OF_CHAPTER -> stringResource(R.string.reader_tts_sleep_end_of_chapter)
    else -> stringResource(R.string.reader_tts_sleep_minutes, minutes)
}

@Composable
private fun errorText(error: TtsException): String = when (error.reason) {
    TtsException.Reason.CONFIGURATION -> stringResource(R.string.reader_tts_error_configuration)
    TtsException.Reason.NETWORK -> stringResource(R.string.reader_tts_error_network)
    TtsException.Reason.HTTP -> stringResource(R.string.reader_tts_error_http, error.statusCode ?: 0)
    TtsException.Reason.ENGINE_UNAVAILABLE -> stringResource(R.string.reader_tts_error_engine)
    TtsException.Reason.INVALID_AUDIO -> stringResource(R.string.reader_tts_error_audio)
}

private fun formatRate(rate: Float): String = String.format(Locale.ROOT, "%.2f", rate).trimEnd('0').trimEnd('.') + "×"

private fun formatRemaining(millis: Long): String {
    val totalSeconds = (millis + 999) / 1000
    return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
