package com.konecto.series76.ui.feature.debug

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainTextTest {
    @Test fun `strips markdown into one line`() {
        val md = "## Result\nThe **761A00-11300000/A** has `700 in-lbs`.\n\n- On/off: __80 Nm__\n* See [datasheet](http://x.y)"
        assertEquals(
            "Result The 761A00-11300000/A has 700 in-lbs. On/off: 80 Nm See datasheet",
            plainText(md),
        )
    }

    @Test fun `plain text is kept`() {
        assertEquals("No model meets 480V.", plainText("  No model meets 480V. "))
    }
}
