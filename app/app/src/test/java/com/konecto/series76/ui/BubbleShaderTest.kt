package com.konecto.series76.ui

import com.konecto.series76.ui.components.background.BubblePresets
import com.konecto.series76.ui.components.background.BubbleShader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleShaderTest {

    private fun rgb(c: Int) = Triple((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)

    @Test fun `gradient without bubbles matches the ramp ends`() {
        val p = BubblePresets.TransitionEnd // Main ramp, bubbles hidden
        assertEquals(Triple(48, 11, 122), rgb(BubbleShader.shade(p, 0, 0, 100, 1000))) // stop 0: (0.19, 0.045, 0.48)
        assertEquals(Triple(6, 2, 11), rgb(BubbleShader.shade(p, 0, 999, 100, 1000))) // stop 3: (0.025, 0.009, 0.045)
    }

    @Test fun `bubbles brighten the area around their origin`() {
        val withBubbles = BubbleShader.shade(BubblePresets.Main, 200, 150, 402, 874)
        val without = BubbleShader.shade(BubblePresets.TransitionEnd, 200, 150, 402, 874)
        val (r1, g1, b1) = rgb(withBubbles)
        val (r0, g0, b0) = rgb(without)
        assertTrue(r1 + g1 + b1 > r0 + g0 + b0)
    }

    @Test fun `rising bubble layer is transparent away from the bubbles`() {
        val c = BubbleShader.shade(BubblePresets.RisingBubbles, 0, 0, 402, 1433)
        assertEquals(0, (c ushr 24) and 0xFF)
    }

    @Test fun `every uniform is declared in the AGSL source`() {
        BubbleShader.uniforms(BubblePresets.Main, 402f, 874f).keys.forEach { name ->
            assertTrue("missing $name", BubbleShader.AGSL.contains("uniform float") && Regex("\\b$name\\b").containsMatchIn(BubbleShader.AGSL))
        }
    }
}
