package com.zenread.core.designsystem.component

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Value-driven slider on top of the [SliderState] API: follows [value] from the
 * caller except while the user is touching it.
 */
@Composable
fun ZenSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val state = remember(steps, valueRange) { SliderState(value = value, steps = steps, trackRange = valueRange) }
    val interaction = remember { MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(value, dragged, pressed) {
        if (!dragged && !pressed) state.value = value
    }
    Slider(
        state = state,
        onValueChange = {
            state.value = it
            onValueChange(it)
        },
        modifier = modifier,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interaction,
    )
}
