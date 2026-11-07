package top.onlyquant.tingxie.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode

/**
 * The glint that sweeps over text being tidied (.tidying .pending): faint
 * text with a band of the document's deep colour passing over it every
 * [period] pixels. One shader serves all the text; [at] moves it, and
 * whatever draws the text has to be redrawn for each frame.
 */
class Shimmer(base: Color, glow: Color, private val period: Float) : ShaderBrush() {
    private val shader = LinearGradientShader(
        Offset.Zero, Offset(period, period * .18f),
        listOf(base, base, glow, base, base), listOf(0f, .38f, .5f, .62f, 1f),
        TileMode.Repeated,
    )
    private val matrix = android.graphics.Matrix()

    /** Where the band is, [t] running from 0 to 1 over one sweep. */
    fun at(t: Float) {
        matrix.setTranslate(period * (1 - t), 0f)
        shader.setLocalMatrix(matrix)
    }

    override fun createShader(size: Size): Shader = shader
}
