package com.konecto.series76.ui.components.background

/**
 * Parameters of the Figma shader "Burbujas violetas" (custom ShaderFill of the mockup). Same names and units as in
 * Figma: percentages for positions/sizes, degrees for the angle. Colors are sRGB 0..1.
 */
data class BubbleParams(
    val background: List<RampStop>,
    val bubbles: List<RampStop> = BUBBLE_RAMP,
    val transitionPosition: Float = 45f,
    val transitionSoftness: Float = 80f,
    val gradientAngle: Float = 65f,
    val bubbleCount: Int = 6,
    val bubbleSize: Float = 23f,
    val bubbleOriginX: Float = 50f,
    val bubbleOriginY: Float,
    val upperExtent: Float = 28f,
    val bubbleOpacity: Float = 0.4f,
    val edgeSoftness: Float = 0.1f,
    val glowIntensity: Float = 0.4f,
) {
    init {
        require(background.size in 1..4 && bubbles.size in 1..4) { "The Android port supports up to 4 ramp stops" }
    }
}

data class RampStop(val position: Float, val r: Float, val g: Float, val b: Float, val a: Float = 1f)

private val BUBBLE_RAMP = listOf(
    RampStop(0f, 0.77f, 0.58f, 1f),
    RampStop(0.45f, 0.48f, 0.23f, 0.87f),
    RampStop(1f, 0.23f, 0.055f, 0.56f),
)

private val MAIN_RAMP = listOf(
    RampStop(0f, 0.19f, 0.045f, 0.48f),
    RampStop(0.26f, 0.29f, 0.065f, 0.62f),
    RampStop(0.57f, 0.075f, 0.015f, 0.17f),
    RampStop(1f, 0.025f, 0.009f, 0.045f),
)

private val LOGIN_RAMP = listOf(
    RampStop(0f, 0.19f, 0.045f, 0.48f),
    RampStop(0.7403846f, 0.29f, 0.065f, 0.62f),
    RampStop(1f, 0.075f, 0.015f, 0.17f),
)

/** Values taken from get_design_context of each Figma frame. */
object BubblePresets {
    /** Chat Screen, Delete, Conversation, Debug screens (bubbles at the top). */
    val Main = BubbleParams(background = MAIN_RAMP, bubbleOriginY = 18f)

    /** Login (bubbles at the bottom). */
    val Login = BubbleParams(background = LOGIN_RAMP, bubbleOriginY = 82f)

    // Layers of "Motion · Transition 1–7" (35:156): two gradients that cross-fade + a bubble layer that rises.
    val TransitionStart = Login.copy(bubbleOpacity = 0f)
    val TransitionEnd = Main.copy(bubbleOpacity = 0f)
    val RisingBubbles = Login.copy(
        background = LOGIN_RAMP.map { it.copy(a = 0f) },
        bubbleOriginY = 50f,
        upperExtent = 17.073172f,
    )

    /** The rising-bubble layer is 1433.36 px tall in a 874 px frame and moves up 559.36 px. */
    const val RISING_LAYER_HEIGHT_RATIO = 1433.36f / 874f
    const val RISING_LAYER_TRAVEL_RATIO = 559.36f / 874f
}
