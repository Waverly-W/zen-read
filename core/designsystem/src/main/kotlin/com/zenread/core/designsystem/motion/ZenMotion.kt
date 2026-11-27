package com.zenread.core.designsystem.motion

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset

/**
 * The app's one set of transitions, so every screen change and every popup moves the same way.
 *
 * Movement uses the theme's Expressive [MotionScheme] springs. Fades use short Material
 * timings: the outgoing content is gone within 90 ms and the incoming content fades in after
 * it, so two half-transparent screens never sit on top of each other (the stiff look of a
 * plain cross-fade).
 */
object ZenMotion {
    private const val EXIT_FADE_MS = 90
    private const val ENTER_FADE_MS = 210

    private fun <T> exitFade() = tween<T>(EXIT_FADE_MS, easing = FastOutLinearInEasing)
    private fun <T> enterFade() = tween<T>(ENTER_FADE_MS, delayMillis = EXIT_FADE_MS, easing = LinearOutSlowInEasing)

    // --- Screen transitions -------------------------------------------------------------------

    /** Opening a deeper screen: Material shared axis X, new content from the right. */
    fun forward(motion: MotionScheme, slidePx: Int): ContentTransform =
        (slideInHorizontally(motion.defaultSpatialSpec<IntOffset>()) { slidePx } + fadeIn(enterFade())) togetherWith
            (slideOutHorizontally(motion.defaultSpatialSpec<IntOffset>()) { -slidePx } + fadeOut(exitFade()))

    /** Going back: the mirror of [forward]. */
    fun back(motion: MotionScheme, slidePx: Int): ContentTransform =
        (slideInHorizontally(motion.defaultSpatialSpec<IntOffset>()) { -slidePx } + fadeIn(enterFade())) togetherWith
            (slideOutHorizontally(motion.defaultSpatialSpec<IntOffset>()) { slidePx } + fadeOut(exitFade()))

    /**
     * Back gesture, driven by the finger: the same motion as [back], plus the leaving screen
     * shrinks slightly so the gesture has visible feedback before it commits.
     */
    fun predictiveBack(motion: MotionScheme, slidePx: Int): ContentTransform {
        val base = back(motion, slidePx)
        return base.targetContentEnter togetherWith
            (base.initialContentExit + scaleOut(motion.defaultSpatialSpec(), targetScale = 0.9f))
    }

    /** Switching between top-level tabs: Material fade through, no sideways movement. */
    fun fadeThrough(motion: MotionScheme): ContentTransform =
        (fadeIn(enterFade()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.94f)) togetherWith
            fadeOut(exitFade())

    // --- Popups (bars, menus, panels) ---------------------------------------------------------

    val enterFromTop: EnterTransition
        @Composable get() = slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { -it } +
            fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec())

    val exitToTop: ExitTransition
        @Composable get() = slideOutVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { -it } +
            fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())

    val enterFromBottom: EnterTransition
        @Composable get() = slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { it } +
            fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec())

    val exitToBottom: ExitTransition
        @Composable get() = slideOutVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { it } +
            fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())

    /** Small popups anchored to content (selection menus): grow from 92% and fade. */
    val popIn: EnterTransition
        @Composable get() = scaleIn(MaterialTheme.motionScheme.fastSpatialSpec(), initialScale = 0.92f) +
            fadeIn(MaterialTheme.motionScheme.fastEffectsSpec())

    val popOut: ExitTransition
        @Composable get() = scaleOut(MaterialTheme.motionScheme.fastSpatialSpec(), targetScale = 0.92f) +
            fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())

    /** Inline sections that open in place (e.g. collapsible text). */
    val expand: EnterTransition
        @Composable get() = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
            fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec())

    val collapse: ExitTransition
        @Composable get() = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) +
            fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())
}
