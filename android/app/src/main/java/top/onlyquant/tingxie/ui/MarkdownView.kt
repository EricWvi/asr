package top.onlyquant.tingxie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.model.markdownExtendedSpans
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import top.onlyquant.tingxie.ui.theme.Tint
import top.onlyquant.tingxie.ui.theme.Tokens

/*
 * View mode: the document rendered with multiplatform-markdown-renderer,
 * styled after .md in design/android.html. Task boxes toggle the source.
 */

private val bodySize = 17.sp
private val bodyLine = 1.75.em
private val mono = FontFamily.Monospace

/** Space above and below a top-level block, collapsing like CSS margins. */
private fun margins(n: ASTNode): Pair<Dp, Dp> = when (n.type) {
    MarkdownElementTypes.ATX_1, MarkdownElementTypes.SETEXT_1 -> 28.dp to 10.dp
    MarkdownElementTypes.ATX_2, MarkdownElementTypes.SETEXT_2 -> 26.dp to 8.dp
    MarkdownElementTypes.ATX_3 -> 20.dp to 6.dp
    MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6 -> 16.dp to 4.dp
    MarkdownTokenTypes.HORIZONTAL_RULE -> 24.dp to 24.dp
    else -> 0.dp to 12.dp
}

private fun ASTNode.isBlank() = type == MarkdownTokenTypes.EOL || type == MarkdownTokenTypes.WHITE_SPACE

/**
 * @param server resolves site-relative image links (/api/images/…).
 * @param hits search words to mark in the text.
 * @param onToggle flips the task box whose "[" is at the given source offset.
 * @param onBlock reports where each top-level block starts, to scroll to a hit.
 */
@Composable
fun MarkdownView(
    content: String,
    tint: Tint,
    server: String,
    hits: List<String>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
    overlays: List<Overlay> = emptyList(),
    freshAlpha: () -> Float = { 1f },
    onBlock: ((startOffset: Int, endOffset: Int, y: Float) -> Unit)? = null,
) {
    val p = Tokens.palette
    val text = TextStyle(fontSize = bodySize, lineHeight = bodyLine, color = p.ink)
    val typography = markdownTypography(
        h1 = text.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 1.35.em),
        h2 = text.copy(fontSize = 21.sp, fontWeight = FontWeight.W600, lineHeight = 1.4.em),
        h3 = text.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 1.5.em),
        h4 = text.copy(fontWeight = FontWeight.SemiBold),
        h5 = text.copy(fontWeight = FontWeight.SemiBold),
        h6 = text.copy(fontWeight = FontWeight.SemiBold, color = p.ink2),
        text = text,
        code = TextStyle(fontFamily = mono, fontSize = 13.5.sp, lineHeight = 1.7.em, color = p.ink),
        inlineCode = text.copy(fontFamily = mono, fontSize = 14.5.sp),
        quote = text.copy(color = p.ink2),
        paragraph = text,
        ordered = text.copy(color = p.ink3),
        bullet = text,
        list = text,
        textLink = TextLinkStyles(SpanStyle(color = p.accent)),
        table = text.copy(fontSize = 15.sp, lineHeight = 1.5.em),
    )
    val colors = markdownColor(
        text = p.ink,
        codeBackground = p.codeBlock,
        inlineCodeBackground = p.code,
        dividerColor = p.line,
        tableBackground = Color.Transparent,
        highlightText = p.ink,
        highlightBackground = tint.mark,
    )
    val hitBg = tint.deep.copy(alpha = .35f)
    val codeSpan = SpanStyle(fontFamily = mono, fontSize = 14.5.sp, background = p.code)
    val annotator = remember(hits, hitBg, codeSpan, overlays) {
        markdownAnnotator(markdownAnnotatorConfig(eolAsNewLine = true)) { content, child ->
            when (child.type) {
                // Inline code: a thin gap instead of the renderer's full spaces.
                MarkdownElementTypes.CODE_SPAN -> {
                    val inner = child.children.filter { it.type != MarkdownTokenTypes.BACKTICK }
                    pushStyle(codeSpan)
                    append('\u202F')
                    if (inner.isNotEmpty()) append(content.substring(inner.first().startOffset, inner.last().endOffset))
                    append('\u202F')
                    pop()
                    true
                }
                // Only [text](url) is a link; [text], [text][ref] and footnotes stay text.
                MarkdownElementTypes.SHORT_REFERENCE_LINK, MarkdownElementTypes.FULL_REFERENCE_LINK -> {
                    append(child.getTextInNode(content).toString())
                    true
                }
                MarkdownTokenTypes.TEXT, MarkdownTokenTypes.COLON, MarkdownTokenTypes.EXCLAMATION_MARK,
                MarkdownTokenTypes.LPAREN, MarkdownTokenTypes.RPAREN,
                MarkdownTokenTypes.SINGLE_QUOTE, MarkdownTokenTypes.DOUBLE_QUOTE -> {
                    val over = overlays.filter { it.from < child.endOffset && it.to > child.startOffset }
                    if (hits.isEmpty() && over.isEmpty()) return@markdownAnnotator false
                    val text = child.getTextInNode(content).toString()
                    append(buildAnnotatedString {
                        append(marked(text, hits, hitBg))
                        for (o in over) {
                            val a = maxOf(o.from, child.startOffset) - child.startOffset
                            val b = minOf(o.to, child.endOffset) - child.startOffset
                            o.style?.let { addStyle(it, a, b) }
                            o.tag?.let { addStringAnnotation(it, o.item, a, b) }
                        }
                    })
                    true
                }
                else -> false
            }
        }
    }
    val transformer = remember(server) { RelativeImages(server) }
    val components = remember(tint, p, server) { components(tint, server, onToggle) }

    // While new text parses the old stays up, so the height and scroll position survive.
    Markdown(
        markdownState = rememberMarkdownState(content, flavour = flavour, parser = parser, retainState = true),
        colors = colors,
        typography = typography,
        padding = markdownPadding(block = 0.dp, list = 0.dp, listItemTop = 2.dp, listItemBottom = 2.dp, listIndent = 0.dp),
        imageTransformer = transformer,
        annotator = annotator,
        extendedSpans = markdownExtendedSpans {
            remember { ExtendedSpans(Backdrops(freshAlpha)) }
        },
        components = components,
        modifier = modifier,
        success = { state, comps, mod ->
            Column(mod) {
                var below = 0.dp
                var first = true
                for (node in state.node.children) {
                    if (node.isBlank()) continue
                    val (top, bottom) = margins(node)
                    Spacer(Modifier.height(if (first) 0.dp else maxOf(top, below)))
                    first = false
                    below = bottom
                    Box(
                        if (onBlock == null) Modifier
                        else Modifier.onGloballyPositioned { onBlock(node.startOffset, node.endOffset, it.positionInParent().y) },
                    ) {
                        if (node.type == MarkdownElementTypes.LINK_DEFINITION) {
                            Text(node.getTextInNode(state.content).toString(), style = LocalMarkdownTypography.current.paragraph)
                        } else {
                            MarkdownElement(node, comps, state.content, includeSpacer = false)
                        }
                    }
                }
            }
        },
    )
}

/** One flavour and parser for every view, so a recomposition does not parse again. */
private val flavour = CjkFlavour()
private val parser = org.intellij.markdown.parser.MarkdownParser(flavour, cancellationToken = org.intellij.markdown.parser.CancellationToken.NonCancellable)

/** A style or a drawn decoration over [from, to) of the source. */
data class Overlay(val from: Int, val to: Int, val style: SpanStyle? = null, val tag: String? = null, val item: String = "")

/** Site-relative image links load from the server, with the device token. */
private class RelativeImages(private val server: String) : ImageTransformer {
    @Composable
    override fun transform(link: String): ImageData? = Coil3ImageTransformerImpl.transform(absolute(server, link))

    @Composable
    override fun intrinsicSize(painter: androidx.compose.ui.graphics.painter.Painter) = Coil3ImageTransformerImpl.intrinsicSize(painter)
}

fun absolute(server: String, link: String) = if (link.startsWith("/")) server + link else link

private fun components(tint: Tint, server: String, onToggle: (Int) -> Unit): MarkdownComponents = markdownComponents(
    heading1 = { Heading(it.content, it.node, it.typography.h1) },
    heading2 = { Heading(it.content, it.node, it.typography.h2) },
    heading3 = { Heading(it.content, it.node, it.typography.h3) },
    heading4 = { Heading(it.content, it.node, it.typography.h4) },
    heading5 = { Heading(it.content, it.node, it.typography.h5) },
    heading6 = { Heading(it.content, it.node, it.typography.h6) },
    setextHeading1 = { Heading(it.content, it.node, it.typography.h1) },
    setextHeading2 = { Heading(it.content, it.node, it.typography.h2) },
    paragraph = { Paragraph(it.content, it.node, server) },
    unorderedList = { MdList(it.content, it.node, ordered = false, tint, onToggle) },
    orderedList = { MdList(it.content, it.node, ordered = true, tint, onToggle) },
    blockQuote = { Quote(it.content, it.node) },
    codeFence = { CodeFence(it.content, it.node) },
    codeBlock = { CodeFence(it.content, it.node) },
    horizontalRule = { Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.palette.line)) },
    table = { Table(it.content, it.node) },
)

/** Inline content of a node, with the renderer's own annotations (links, emphasis, ==mark==). */
@Composable
private fun inline(content: String, node: ASTNode, style: TextStyle): AnnotatedString {
    val settings = annotatorSettings()
    return buildAnnotatedString {
        pushStyle(style.toSpanStyle())
        buildMarkdownAnnotatedString(content, node.children, settings)
        pop()
    }
}

/** Without leading and trailing blanks, keeping the styles in place. */
private fun AnnotatedString.trimmed(): AnnotatedString {
    val start = text.indexOfFirst { !it.isWhitespace() }
    if (start < 0) return AnnotatedString("")
    return subSequence(start, text.indexOfLast { !it.isWhitespace() } + 1)
}

@Composable
private fun Heading(content: String, node: ASTNode, style: TextStyle) {
    // ATX: [ATX_HEADER, ATX_CONTENT]; setext: [SETEXT_CONTENT, EOL, SETEXT_n].
    val inner = node.children.firstOrNull { it.type == MarkdownTokenTypes.ATX_CONTENT || it.type == MarkdownTokenTypes.SETEXT_CONTENT } ?: return
    Text(inline(content, inner, style).trimmed(), style = style)
}

/** A paragraph, or a picture when it holds nothing but one image. */
@Composable
private fun Paragraph(content: String, node: ASTNode, server: String) {
    val parts = node.children.filterNot { it.isBlank() }
    val img = parts.singleOrNull()?.takeIf { it.type == MarkdownElementTypes.IMAGE }
    if (img != null) {
        val link = img.findDest(content)
        if (link != null) {
            val p = Tokens.palette
            AsyncImage(
                absolute(server, link), contentDescription = null, contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, p.line, RoundedCornerShape(12.dp)),
            )
            return
        }
    }
    MarkdownParagraph(content, node, style = LocalMarkdownTypography.current.paragraph)
}

private fun ASTNode.findDest(content: String): String? {
    fun walk(n: ASTNode): ASTNode? = if (n.type == MarkdownElementTypes.LINK_DESTINATION) n else n.children.firstNotNullOfOrNull(::walk)
    return walk(this)?.getTextInNode(content)?.toString()?.trim('<', '>')
}

/**
 * A list: dots (hollow when nested), muted numbers, or task boxes. Done
 * tasks read muted and struck through.
 */
@Composable
private fun MdList(content: String, node: ASTNode, ordered: Boolean, tint: Tint, onToggle: (Int) -> Unit, depth: Int = 0) {
    val p = Tokens.palette
    val comps = LocalMarkdownComponents.current
    val typo = LocalMarkdownTypography.current
    val colors = LocalMarkdownColors.current
    val start = node.children.firstOrNull { it.type == MarkdownElementTypes.LIST_ITEM }
        ?.children?.firstOrNull { it.type == MarkdownTokenTypes.LIST_NUMBER }
        ?.getTextInNode(content)?.takeWhile(Char::isDigit)?.toString()?.toIntOrNull() ?: 1
    val nested = remember(comps) {
        markdownComponents(
            heading1 = comps.heading1, heading2 = comps.heading2, heading3 = comps.heading3,
            heading4 = comps.heading4, heading5 = comps.heading5, heading6 = comps.heading6,
            paragraph = comps.paragraph, blockQuote = comps.blockQuote, codeFence = comps.codeFence,
            codeBlock = comps.codeBlock, horizontalRule = comps.horizontalRule, table = comps.table,
            unorderedList = { MdList(it.content, it.node, ordered = false, tint, onToggle, depth + 1) },
            orderedList = { MdList(it.content, it.node, ordered = true, tint, onToggle, depth + 1) },
        )
    }
    Column {
        var index = 0
        for (item in node.children) {
            if (item.type != MarkdownElementTypes.LIST_ITEM) continue
            val box = item.children.firstOrNull { it.type == GFMTokenTypes.CHECK_BOX }
            val done = box != null && box.getTextInNode(content).contains(Regex("\\[[xX]]"))
            val number = start + index++
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                when {
                    box != null -> TaskBox(done) { onToggle(box.startOffset) }
                    ordered -> Text(
                        "$number.", style = typo.ordered, color = p.ink3,
                        modifier = Modifier.width(24.dp),
                    )
                    else -> Box(Modifier.width(24.dp).height(with(androidx.compose.ui.platform.LocalDensity.current) { (bodySize.toPx() * 1.75f).toDp() }), contentAlignment = Alignment.CenterStart) {
                        Box(
                            Modifier
                                .offset(x = 4.dp)
                                .size(6.dp)
                                .drawBehind {
                                    if (depth == 0) drawCircle(p.ink3)
                                    else drawCircle(p.ink3, radius = size.minDimension / 2 - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                                },
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    val doneTypo = if (done) remember(typo) { struck(typo, p.ink3) } else typo
                    val doneColors = if (done) remember(colors) { mutedColors(colors, p.ink3) } else colors
                    CompositionLocalProvider(LocalMarkdownTypography provides doneTypo, LocalMarkdownColors provides doneColors) {
                        for (child in item.children) {
                            when (child.type) {
                                MarkdownTokenTypes.LIST_BULLET, MarkdownTokenTypes.LIST_NUMBER, GFMTokenTypes.CHECK_BOX,
                                MarkdownTokenTypes.EOL, MarkdownTokenTypes.WHITE_SPACE -> Unit
                                else -> MarkdownElement(child, nested, content, includeSpacer = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun struck(t: com.mikepenz.markdown.model.MarkdownTypography, c: Color) = markdownTypographyCopy(t) {
    it.copy(color = c, textDecoration = TextDecoration.LineThrough)
}

private fun markdownTypographyCopy(t: com.mikepenz.markdown.model.MarkdownTypography, f: (TextStyle) -> TextStyle) =
    com.mikepenz.markdown.model.DefaultMarkdownTypography(
        h1 = f(t.h1), h2 = f(t.h2), h3 = f(t.h3), h4 = f(t.h4), h5 = f(t.h5), h6 = f(t.h6),
        text = f(t.text), code = t.code, inlineCode = f(t.inlineCode), quote = f(t.quote),
        paragraph = f(t.paragraph), ordered = f(t.ordered), bullet = f(t.bullet), list = f(t.list),
        textLink = t.textLink, table = t.table, alertTitle = t.alertTitle,
    )

private fun mutedColors(c: com.mikepenz.markdown.model.MarkdownColors, ink: Color) = com.mikepenz.markdown.model.DefaultMarkdownColors(
    text = ink, codeBackground = c.codeBackground, inlineCodeBackground = c.inlineCodeBackground,
    dividerColor = c.dividerColor, tableBackground = c.tableBackground, alert = c.alert,
    highlightText = c.highlightText, highlightBackground = c.highlightBackground,
)

/** A 20dp task box with a 40dp touch target. */
@Composable
private fun TaskBox(done: Boolean, onToggle: () -> Unit) {
    val p = Tokens.palette
    Box(
        Modifier
            .width(32.dp)
            .toggleable(done, remember { MutableInteractionSource() }, indication = null, role = Role.Checkbox) { onToggle() },
    ) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (done) p.accent else Color.Transparent)
                .border(1.75.dp, if (done) p.accent else p.ink3.copy(alpha = .7f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (done) androidx.compose.material3.Icon(
                remember { checkIcon() }, null, tint = Color.White, modifier = Modifier.size(13.dp),
            )
        }
    }
}

private fun checkIcon() = androidx.compose.ui.graphics.vector.ImageVector.Builder("check", 24.dp, 24.dp, 24f, 24f)
    .addPath(
        androidx.compose.ui.graphics.vector.PathParser().parsePathString("M5 12l5 5 9-10").toNodes(),
        stroke = androidx.compose.ui.graphics.SolidColor(Color.Black), strokeLineWidth = 3f,
        strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
    ).build()

@Composable
private fun Quote(content: String, node: ASTNode) {
    val p = Tokens.palette
    val comps = LocalMarkdownComponents.current
    val typo = LocalMarkdownTypography.current
    val colors = LocalMarkdownColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(p.paperSoft)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        CompositionLocalProvider(
            LocalMarkdownTypography provides remember(typo) { markdownTypographyCopy(typo) { it.copy(color = p.ink2) } },
            LocalMarkdownColors provides remember(colors) { mutedColors(colors, p.ink2) },
        ) {
            var first = true
            for (child in node.children) {
                if (child.isBlank() || child.type == MarkdownTokenTypes.BLOCK_QUOTE) continue
                if (!first) Spacer(Modifier.height(8.dp))
                first = false
                MarkdownElement(child, comps, content, includeSpacer = false)
            }
        }
    }
}

@Composable
private fun CodeFence(content: String, node: ASTNode) {
    val p = Tokens.palette
    val typo = LocalMarkdownTypography.current
    val lang = node.children.firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }?.getTextInNode(content)?.toString()?.trim()
    val lines = node.children.filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT || it.type == MarkdownTokenTypes.CODE_LINE }
    val code = if (lines.isEmpty()) "" else content.substring(lines.first().startOffset, lines.last().endOffset)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(p.codeBlock),
    ) {
        Text(
            code.trimEnd('\n').replace("\t", "    "), style = typo.code, softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
        )
        if (!lang.isNullOrEmpty()) {
            Text(lang, color = p.ink3, fontSize = 12.sp, lineHeight = 12.sp, modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 12.dp))
        }
    }
}

/** A plain table: header in semibold, a hairline under every row, columns as wide as they need. */
@Composable
private fun Table(content: String, node: ASTNode) {
    val p = Tokens.palette
    val style = LocalMarkdownTypography.current.table
    val rows = node.children.filter { it.type == GFMElementTypes.HEADER || it.type == GFMElementTypes.ROW }
    val aligns = node.children.firstOrNull { it.type == GFMTokenTypes.TABLE_SEPARATOR }
        ?.getTextInNode(content)?.toString()?.trim()?.trim('|')?.split('|')?.map { c ->
            val t = c.trim()
            when {
                t.startsWith(':') && t.endsWith(':') -> Alignment.Center
                t.endsWith(':') -> Alignment.CenterEnd
                else -> Alignment.CenterStart
            }
        } ?: emptyList()
    val grid = rows.map { r -> r.children.filter { it.type == GFMTokenTypes.CELL } }
    val cols = grid.maxOfOrNull { it.size } ?: 0
    if (cols == 0) return
    Box(Modifier.horizontalScroll(rememberScrollState())) {
        Layout(
            content = {
                grid.forEachIndexed { ri, cells ->
                    for (ci in 0 until cols) {
                        val cell = cells.getOrNull(ci)
                        val s = if (ri == 0) style.copy(fontWeight = FontWeight.SemiBold) else style
                        Box(Modifier.drawBehind {
                            drawLine(p.line, Offset(0f, size.height - .5.dp.toPx()), Offset(size.width, size.height - .5.dp.toPx()), 1.dp.toPx())
                        }, contentAlignment = aligns.getOrNull(ci) ?: Alignment.CenterStart) {
                            Text(
                                if (cell == null) AnnotatedString("") else inline(content, cell, s).trimmed(),
                                style = s, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp, end = if (ci == cols - 1) 0.dp else 14.dp),
                            )
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val maxCell = 280.dp.roundToPx()
            val widths = IntArray(cols)
            for ((i, m) in measurables.withIndex()) widths[i % cols] = maxOf(widths[i % cols], minOf(m.maxIntrinsicWidth(Constraints.Infinity), maxCell))
            val heights = grid.indices.map { r -> (0 until cols).maxOf { c -> measurables[r * cols + c].maxIntrinsicHeight(widths[c]) } }
            val ps = grid.indices.flatMap { r -> (0 until cols).map { c -> measurables[r * cols + c].measure(Constraints.fixed(widths[c], heights[r])) } }
            layout(widths.sum(), heights.sum()) {
                var y = 0
                for (r in grid.indices) {
                    var x = 0
                    for (c in 0 until cols) {
                        ps[r * cols + c].place(x, y)
                        x += widths[c]
                    }
                    y += heights[r]
                }
            }
        }
    }
}
