package top.onlyquant.tingxie.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

/**
 * The glint that sweeps over text being tidied (.tidying .pending): faint
 * text with a band of the document's deep colour passing over it every
 * [period] pixels. One shader serves all the text; [at] moves it, and
 * whatever draws the text has to be redrawn for each frame.
 *
 * The band stands upright: a Text draws each of its paragraphs (one per
 * line break) moved further down, which a slanted band would show as a
 * break at every line; an upright one does not change with height.
 */
class Shimmer(base: Color, glow: Color, private val period: Float) : ShaderBrush() {
    private val shader = LinearGradientShader(
        Offset.Zero, Offset(period, 0f),
        listOf(base, base, glow, base, base), listOf(0f, .38f, .5f, .62f, 1f),
        TileMode.Repeated,
    )
    private val matrix = android.graphics.Matrix()

    /**
     * Where the band is, [t] running from 0 to 1 over one sweep, for text
     * drawn at [origin] on screen. The shader works in the coordinates of
     * whatever draws the text, so each text shifts it back by its origin and
     * all of them share one band, as background-attachment: fixed does.
     */
    fun at(t: Float, origin: Offset = Offset.Zero) {
        matrix.setTranslate(period * (1 - t) - origin.x, 0f)
        shader.setLocalMatrix(matrix)
    }

    override fun createShader(size: Size): Shader = shader
}

/** The shimmer while tidying and where its sweep is. */
class Glow(val shimmer: Shimmer, val sweep: () -> Float)

/** Set while tidying; text that may glow anchors the band with [glowAnchor]. */
val LocalGlow = compositionLocalOf<Glow?> { null }

/**
 * Lines the shimmer up with the screen for the text drawn right here (no
 * padding between this and the text), just before it draws.
 */
@Composable
fun Modifier.glowAnchor(): Modifier {
    val g = LocalGlow.current ?: return this
    val origin = remember { floatArrayOf(0f, 0f) }
    return onGloballyPositioned { c -> c.positionInRoot().let { origin[0] = it.x; origin[1] = it.y } }
        .drawWithContent {
            g.shimmer.at(g.sweep(), Offset(origin[0], origin[1]))
            drawContent()
        }
}
