package top.onlyquant.tingxie.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens of design/android.html, resolved by Chromium so the oklch
 * mixes match the prototype exactly (.tmp/android-tools/colors_browser.py).
 */
@Immutable
data class Palette(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val line: Color,
    val chip: Color,
    val accent: Color,
    val rec: Color,
    val paper: Color,
    val paperSoft: Color,
    val paperLine: Color,
    val hover: Color,
    val selected: Color,
    /** Chip and icon-button wells on paper: rgb(0 0 0 / .045) or rgb(255 255 255 / .07). */
    val well: Color,
    /** Inline code and code block grounds. */
    val code: Color,
    val codeBlock: Color,
)

val LightPalette = Palette(
    dark = false,
    bg = Color(0xFFFAF8F5),
    surface = Color(0xFFFFFFFF),
    ink = Color(0xFF1D1D1F),
    ink2 = Color(0xFF6E6E73),
    ink3 = Color(0xFFA1A1A6),
    line = Color(0xFFEBE8E3),
    chip = Color(0xFFF2F1EF),
    accent = Color(0xFF1A7CF5),
    rec = Color(0xFFF2544B),
    paper = Color(0xFFFFFDF5),
    paperSoft = Color(0xFFFEF7E0),
    paperLine = Color(0xFFF2E8C4),
    hover = Color(0x09000000),
    selected = Color(0x0F000000),
    well = Color(0x0B000000),
    code = Color(0x0D000000),
    codeBlock = Color(0x0A000000),
)

val DarkPalette = Palette(
    dark = true,
    bg = Color(0xFF1B1B1D),
    surface = Color(0xFF2A2A2D),
    ink = Color(0xFFF2F2F4),
    ink2 = Color(0xFFA0A0A7),
    ink3 = Color(0xFF6C6C72),
    line = Color(0xFF3A3A3E),
    chip = Color(0xFF36363A),
    accent = Color(0xFF3B8EF7),
    rec = Color(0xFFFF6258),
    paper = Color(0xFF302F2B),
    paperSoft = Color(0xFF393730),
    paperLine = Color(0xFF5A5648),
    hover = Color(0x0AFFFFFF),
    selected = Color(0x14FFFFFF),
    well = Color(0x12FFFFFF),
    code = Color(0x12FFFFFF),
    codeBlock = Color(0x0DFFFFFF),
)

/** A document colour and the shades derived from it (.tinted in the prototype). */
@Immutable
data class Tint(
    val name: String,
    /** The pastel itself: palette dots and the colour picker. */
    val base: Color,
    /** List swatches and filter dots. */
    val fill: Color,
    /** Markdown markers, the online dot, fresh-text underlines. */
    val deep: Color,
    /** Text selection. */
    val sel: Color,
    /** ==highlight== and search hits in the list. */
    val mark: Color,
)

/** The six document colours, in the order the pickers show them. */
val TintNames = listOf("butter", "peach", "rose", "periwinkle", "sky", "mint")

private val lightTints = listOf(
    Tint("butter", Color(0xFFFBE69B), Color(0xFFFBE69B), Color(0xFFA27B00), Color(0x73FBE79B), Color(0xFFFBE69B)),
    Tint("peach", Color(0xFFFFD5BD), Color(0xFFFFD5BD), Color(0xFFB16D44), Color(0x73FFD5BC), Color(0xFFFFD5BD)),
    Tint("rose", Color(0xFFF8C8DA), Color(0xFFF8C8DA), Color(0xFFB16485), Color(0x73F8C8D9), Color(0xFFF8C8DA)),
    Tint("periwinkle", Color(0xFFB6C4F4), Color(0xFFB6C4F4), Color(0xFF677ACA), Color(0x73B6C3F4), Color(0xFFB6C4F4)),
    Tint("sky", Color(0xFFA8D9F1), Color(0xFFA8D9F1), Color(0xFF218CB4), Color(0x73A9D9F2), Color(0xFFA8D9F1)),
    Tint("mint", Color(0xFFC6EAC9), Color(0xFFC6EAC9), Color(0xFF52915B), Color(0x73C5EBCA), Color(0xFFC6EAC9)),
).associateBy { it.name }

private val darkTints = listOf(
    Tint("butter", Color(0xFFFBE69B), Color(0xFF9E9266), Color(0xFFDABC4A), Color(0x73837131), Color(0xFF6B644D)),
    Tint("peach", Color(0xFFFFD5BD), Color(0xFFA18879), Color(0xFFE8B08F), Color(0x73896856), Color(0xFF6D5F56)),
    Tint("rose", Color(0xFFF8C8DA), Color(0xFF9D808B), Color(0xFFE9A8C1), Color(0x738C6673), Color(0xFF6B5B61)),
    Tint("periwinkle", Color(0xFFB6C4F4), Color(0xFF767E9A), Color(0xFFA8BAFD), Color(0x73667197), Color(0xFF555A6A)),
    Tint("sky", Color(0xFFA8D9F1), Color(0xFF6D8A99), Color(0xFF82C9EA), Color(0x7350788C), Color(0xFF506169)),
    Tint("mint", Color(0xFFC6EAC9), Color(0xFF7F9480), Color(0xFF9BCDA0), Color(0x735D7A5F), Color(0xFF5A665A)),
).associateBy { it.name }

/** The shades of a document colour; unknown names fall back to butter. */
fun Palette.tint(name: String): Tint =
    (if (dark) darkTints else lightTints)[name] ?: (if (dark) darkTints else lightTints).getValue("butter")

val LocalPalette = staticCompositionLocalOf { LightPalette }

object Tokens {
    val palette: Palette @Composable get() = LocalPalette.current
}

@Composable
fun TingxieTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.accent, onPrimary = Color.White, background = p.bg, onBackground = p.ink,
            surface = p.surface, onSurface = p.ink, onSurfaceVariant = p.ink2, outline = p.line,
            error = p.rec, surfaceContainer = p.surface, surfaceContainerHigh = p.surface,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = Color.White, background = p.bg, onBackground = p.ink,
            surface = p.surface, onSurface = p.ink, onSurfaceVariant = p.ink2, outline = p.line,
            error = p.rec, surfaceContainer = p.surface, surfaceContainerHigh = p.surface,
        )
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalPalette provides p,
            LocalTextSelectionColors provides TextSelectionColors(p.accent, p.accent.copy(alpha = 0.3f)),
            content = content,
        )
    }
}
