package com.unciv.app.desktop

import com.unciv.ui.components.fonts.FontFamilyData
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopFontTests {
    @Test
    fun glyphRenderingContainsAntialiasedEdgePixels() {
        val font = DesktopFont()
        font.setFontFamily(FontFamilyData.default, 100)

        val image = font.renderGlyphImageForTesting("Authoritative")
        val alphaValues = image.getRGB(
            0,
            0,
            image.width,
            image.height,
            null,
            0,
            image.width,
        ).asSequence().map { it ushr 24 and 0xff }.toSet()

        assertTrue("Glyph should contain opaque pixels", 255 in alphaValues)
        assertTrue(
            "Glyph edges should contain partially transparent antialiased pixels",
            alphaValues.any { it in 1..254 },
        )
    }
}
