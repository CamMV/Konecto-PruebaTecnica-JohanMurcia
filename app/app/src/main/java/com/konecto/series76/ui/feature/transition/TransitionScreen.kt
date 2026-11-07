package com.konecto.series76.ui.feature.transition

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.konecto.series76.R
import com.konecto.series76.ui.components.background.BubbleBackground
import com.konecto.series76.ui.components.background.BubblePresets

/*
 * "Motion · Transition 1–7" (Figma 35:156, get_motion_context): a 6.1 s timeline. Played once, up to the last
 * keyframe (88.525 %), then it navigates to the chat list. Tap to skip.
 *  - Gradient: login ramp → main ramp (opacity of the main layer, 7.377 % → 88.525 %).
 *  - Bubbles: a layer 1433/874 of the screen tall moves up 559/874 (same window) → bubbles go from bottom to top.
 *  - "K" (letra-k): rotates 90° (screen counter-clockwise, the vector is Y-flipped), scales to 0.538×0.533 and fades.
 *  - Ring (anillo): fades/scales in 0.78 → 1, then turns 361°.
 *  - Dot (punto): fades/scales in 0.65 → 1, then stretches horizontally to 1.42.
 */
private const val TIMELINE_MS = 6100
private const val END = 0.88525f
private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f) // CSS ease-in-out

/** Eased progress of a keyframe segment [from, to] (fractions of the timeline). */
private fun seg(t: Float, from: Float, to: Float): Float = EaseInOut.transform(((t - from) / (to - from)).coerceIn(0f, 1f))

private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

@Composable
fun TransitionScreen(onFinished: () -> Unit) {
    val finish by rememberUpdatedState(onFinished)
    // The saved progress lets the animation resume (not restart) if the screen is recreated.
    var saved by rememberSaveable { mutableFloatStateOf(0f) }
    val progress = remember { Animatable(saved) }
    LaunchedEffect(Unit) {
        val remainingMs = (TIMELINE_MS * (END - progress.value)).toInt().coerceAtLeast(0)
        progress.animateTo(END, tween(durationMillis = remainingMs, easing = LinearEasing)) { saved = value }
        finish() // the NavHost ignores it if a tap already navigated
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() },
    ) {
        val screenH = maxHeight
        BubbleBackground(BubblePresets.TransitionStart, Modifier.fillMaxSize())
        BubbleBackground(
            BubblePresets.TransitionEnd,
            Modifier.fillMaxSize().graphicsLayer { alpha = seg(progress.value, 0.07377f, 0.88525f) },
        )
        BubbleBackground(
            BubblePresets.RisingBubbles,
            Modifier
                .fillMaxWidth()
                .height(screenH * BubblePresets.RISING_LAYER_HEIGHT_RATIO)
                .graphicsLayer {
                    translationY = -seg(progress.value, 0.07377f, 0.88525f) * BubblePresets.RISING_LAYER_TRAVEL_RATIO * size.height /
                        BubblePresets.RISING_LAYER_HEIGHT_RATIO
                },
        )
        // Glyphs are centered in the form area of the frame (status bar 54 + form 820 → center at y = 464 of 874).
        Box(Modifier.fillMaxSize().offset(y = screenH * (464f / 874f - 0.5f)), contentAlignment = Alignment.Center) {
            Image(
                painterResource(R.drawable.splash_k), contentDescription = null,
                modifier = Modifier.size(119.dp, 105.dp).graphicsLayer {
                    val t = progress.value
                    val f = seg(t, 0.07377f, 0.30328f)
                    rotationZ = -90f * f
                    scaleX = lerp(1f, 0.537815f, f)
                    scaleY = lerp(1f, 0.533333f, f)
                    alpha = 1f - seg(t, 0.11475f, 0.30328f)
                },
            )
            Image(
                painterResource(R.drawable.splash_ring), contentDescription = null,
                modifier = Modifier.size(124.dp, 106.dp).graphicsLayer {
                    val t = progress.value
                    val appear = seg(t, 0.31967f, 0.43443f)
                    alpha = appear
                    scaleX = lerp(0.78f, 1f, appear)
                    scaleY = scaleX
                    rotationZ = 361.0f * seg(t, 0.42623f, 0.88525f) // 6.300639 rad
                },
            )
            Image(
                painterResource(R.drawable.splash_dot), contentDescription = null,
                modifier = Modifier.size(38.dp).graphicsLayer {
                    val t = progress.value
                    val appear = seg(t, 0.22951f, 0.34426f)
                    alpha = appear
                    scaleX = lerp(0.65f, 1f, appear) * lerp(1f, 1.421053f, seg(t, 0.7377f, 0.88525f))
                    scaleY = lerp(0.65f, 1f, appear)
                },
            )
        }
    }
}
