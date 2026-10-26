package top.onlyquant.tingxie.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpanPainter
import com.mikepenz.markdown.compose.extendedspans.SpanDrawInstructions

/**
 * Rounded grounds behind ==marks==, search hits and inline code, sized to
 * the glyphs rather than the tall line: marks get 1dp of air around the
 * text and a 3dp corner, code a 6dp corner (as .md mark and .md code).
 * Also underlines text tagged [FRESH] (the item is the colour) 2dp thick and
 * 5dp below the baseline, faded by [freshAlpha] (.fresh).
 */
class Backdrops(private val freshAlpha: () -> Float = { 1f }) : ExtendedSpanPainter() {
    override fun decorate(span: SpanStyle, start: Int, end: Int, text: AnnotatedString, builder: AnnotatedString.Builder): SpanStyle {
        if (span.background == Color.Unspecified || span.background == Color.Transparent) return span
        val code = span.fontFamily == FontFamily.Monospace
        builder.addStringAnnotation(TAG, "${span.background.toArgb()}|${if (code) 1 else 0}", start, end)
        return span.copy(background = Color.Unspecified)
    }

    override fun decorate(linkAnnotation: LinkAnnotation, start: Int, end: Int, text: AnnotatedString, builder: AnnotatedString.Builder) = linkAnnotation

    override fun drawInstructionsFor(layoutResult: TextLayoutResult, color: Color?): SpanDrawInstructions {
        val text = layoutResult.layoutInput.text
        val marks = text.getStringAnnotations(TAG, 0, text.length)
        val fresh = text.getStringAnnotations(FRESH, 0, text.length)
        return SpanDrawInstructions {
            val alpha = if (fresh.isEmpty()) 0f else freshAlpha()
            for (a in fresh) {
                if (alpha <= 0f) break
                val color = Color(a.item.toInt()).copy(alpha = alpha)
                val first = layoutResult.getLineForOffset(a.start)
                val last = layoutResult.getLineForOffset((a.end - 1).coerceAtLeast(a.start))
                for (line in first..last) {
                    val left = if (line == first) layoutResult.getHorizontalPosition(a.start, true) else layoutResult.getLineLeft(line)
                    val right = if (line == last) layoutResult.getHorizontalPosition(a.end, true) else layoutResult.getLineRight(line)
                    if (right <= left) continue
                    val y = layoutResult.getLineBaseline(line) + 5.sp.toPx()
                    drawLine(color, Offset(left, y), Offset(right, y), 2.sp.toPx())
                }
            }
            for (a in marks) {
                val (argb, kind) = a.item.split('|')
                val code = kind == "1"
                val bg = Color(argb.toInt())
                // Measured from the baseline of the 17sp body text.
                val above = (if (code) 16.sp else 18.sp).toPx()
                val below = (if (code) 4.5.sp else 5.sp).toPx()
                val pad = (if (code) 0.sp else 0.sp).toPx()
                val radius = CornerRadius((if (code) 6.sp else 3.sp).toPx())
                val first = layoutResult.getLineForOffset(a.start)
                val last = layoutResult.getLineForOffset((a.end - 1).coerceAtLeast(a.start))
                for (line in first..last) {
                    val left = if (line == first) layoutResult.getHorizontalPosition(a.start, true) else layoutResult.getLineLeft(line)
                    val right = if (line == last) layoutResult.getHorizontalPosition(a.end, true) else layoutResult.getLineRight(line)
                    if (right <= left) continue
                    val base = layoutResult.getLineBaseline(line)
                    drawRoundRect(bg, Offset(left - pad, base - above), Size(right - left + 2 * pad, above + below), radius)
                }
            }
        }
    }

    companion object {
        private const val TAG = "backdrop"
        const val FRESH = "fresh"
    }
}
