package io.github.giansalvo.ggcalendar.feature.widget

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetColorsTest {
    @Test fun sixDigitsAreOpaque() {
        assertEquals(Color(0xFF039BE5), WidgetColors.parse("#039BE5"))
    }

    @Test fun theHashIsOptional() {
        assertEquals(Color(0xFF039BE5), WidgetColors.parse("039be5"))
    }

    @Test fun eightDigitsKeepTheAlpha() {
        assertEquals(Color(0x80039BE5), WidgetColors.parse("#80039BE5"))
    }

    @Test fun anythingElseIsTheFallbackColor() {
        val fallback = Color(0xFF7986CB)
        assertEquals(fallback, WidgetColors.parse(""))
        assertEquals(fallback, WidgetColors.parse("not a color"))
        assertEquals(fallback, WidgetColors.parse("#12345"))
        assertEquals(fallback, WidgetColors.parse("#+39BE5"))
    }

    @Test fun darkTextOnALightColorAndWhiteOnADarkOne() {
        assertEquals(Color(0xFF1F1F1F), WidgetColors.textOn(Color(0xFFFFEB3B)))
        assertEquals(Color.White, WidgetColors.textOn(Color(0xFF1A237E)))
    }
}
