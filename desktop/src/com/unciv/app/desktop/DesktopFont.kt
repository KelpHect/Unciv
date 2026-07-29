package com.unciv.app.desktop

import com.badlogic.gdx.graphics.Pixmap
import com.unciv.UncivGame
import com.unciv.ui.components.fonts.FontFamilyData
import com.unciv.ui.components.fonts.FontImplementation
import com.unciv.ui.components.fonts.FontMetricsCommon
import com.unciv.ui.components.fonts.Fonts
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.GraphicsEnvironment
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.Locale


class DesktopFont : FontImplementation {

    private lateinit var font: Font
    private lateinit var metric: FontMetrics

    override fun setFontFamily(fontFamilyData: FontFamilyData, size: Int) {

        // Mod font
        if (fontFamilyData.filePath != null)
        {
            this.font = createFontFromFile(fontFamilyData.filePath!!, size)
        }
        // System font
        else
        {
            this.font = Font(fontFamilyData.invariantName, Font.PLAIN, size)
        }

        val bufferedImage = BufferedImage(1, 1, BufferedImage.TYPE_4BYTE_ABGR)
        val graphics = bufferedImage.createGraphics()
        graphics.enableHighQualityTextRendering()
        this.metric = graphics.getFontMetrics(font)
        graphics.dispose()
    }

    private fun createFontFromFile(path: String, size: Int): Font {
        var font: Font
        try
        {
            // Try to create and register new font
            val fontFile = UncivGame.Current.files.getLocalFile(path).file()
            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            font = Font.createFont(Font.TRUETYPE_FONT, fontFile).deriveFont(size.toFloat())
            ge.registerFont(font)
        }
        catch (_: Exception)
        {
            // Fallback to default, if failed.
            font = Font(Fonts.DEFAULT_FONT_FAMILY, Font.PLAIN, size)
        }
        return font
    }

    override fun getFontSize(): Int {
        return font.size
    }

    override fun getCharPixmap(char: Char) = getCharPixmapCommon(char.toString(), metric.charWidth(char))

    override fun getCharPixmap(symbolString: String) = getCharPixmapCommon(symbolString, metric.stringWidth(symbolString))

    private fun getCharPixmapCommon(symbolString: String, measuredWidth: Int): Pixmap {
        var width = measuredWidth
        var height = metric.height
        if (width == 0) {
            // This happens e.g. for the Tab character
            height = font.size
            width = height
        }

        val bi = renderGlyphImage(symbolString, width, height)
        val pixmap = Pixmap(bi.width, bi.height, Pixmap.Format.RGBA8888)
        val data = bi.getRGB(0, 0, bi.width, bi.height, null, 0, bi.width)
        for (i in 0 until bi.width) {
            for (j in 0 until bi.height) {
                pixmap.setColor(Integer.reverseBytes(data[i + (j * bi.width)]))
                pixmap.drawPixel(i, j)
            }
        }
        return pixmap
    }

    internal fun renderGlyphImageForTesting(symbolString: String): BufferedImage =
        renderGlyphImage(symbolString, metric.stringWidth(symbolString), metric.height)

    private fun renderGlyphImage(
        symbolString: String,
        width: Int,
        height: Int,
    ): BufferedImage {
        val bi = BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR)
        val g = bi.createGraphics()
        g.enableHighQualityTextRendering()
        g.font = font
        g.color = Color.WHITE
        g.drawString(symbolString, 0, metric.leading + metric.ascent)
        g.dispose()
        return bi
    }

    private fun Graphics2D.enableHighQualityTextRendering() {
        setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
        )
        setRenderingHint(
            RenderingHints.KEY_FRACTIONALMETRICS,
            RenderingHints.VALUE_FRACTIONALMETRICS_ON,
        )
        setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        setRenderingHint(
            RenderingHints.KEY_ALPHA_INTERPOLATION,
            RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY,
        )
    }

    override fun getSystemFonts(): Sequence<FontFamilyData> {
        val cjkLanguage = " CJK " +System.getProperty("user.language").uppercase()
        return GraphicsEnvironment.getLocalGraphicsEnvironment().allFonts.asSequence()
            .filter { " CJK " !in it.fontName || cjkLanguage in it.fontName }
            .map { FontFamilyData(it.family, it.getFamily(Locale.ROOT)) }
            .distinctBy { it.invariantName }
    }

    // Note: AWT uses the FontDesignMetrics implementation in our case, which has more precise
    // float fields but rounds to integers to satisfy the interface.
    // Additionally, the rounding is weird: x.049 rounds down, x.051 rounds up.
    // There is no way around the privacy crap: FontUtilities.getFont2D(metric.font).getStrike(metric.font, metric.fontRenderContext).getFontMetrics() would work if that last method wasn't private too...
    // Reflection is out too, since java.desktop refuses to open sun.font - we must die with rounding errors!
    override fun getMetrics() = FontMetricsCommon(
        ascent = metric.ascent.toFloat(),
        descent = metric.descent.toFloat(),
        height = metric.height.toFloat(),
        leading = metric.leading.toFloat()
    )
}
