package top.onlyquant.tingxie.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import top.onlyquant.tingxie.ui.theme.Palette
import top.onlyquant.tingxie.ui.theme.Tint

/*
 * Edit mode: the Markdown source with its syntax coloured, as .src in
 * design/android.html. Markers stay visible in the document's deep tint;
 * headings are larger, code is monospaced, ==marks== get their ground.
 */

private val fence = Regex("^ {0,3}(`{3,}|~{3,})")
private val heading = Regex("^ {0,3}(#{1,6})( |$)")
private val listItem = Regex("^(\\s*)([-*+]|\\d{1,9}[.)])( \\[[ xX]])?( |$)")
private val quote = Regex("^ {0,3}(> ?)+")
private val rule = Regex("^ {0,3}([-*_])( *\\1){2,} *$")
private val tableRow = Regex("^\\s*\\|.*\\|\\s*$")

private val inline = listOf(
    Regex("`+") to "code",
    Regex("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*") to "strong",
    Regex("__(?=\\S)(.+?)(?<=\\S)__") to "strong",
    Regex("(?<![*\\w])\\*(?=[^\\s*])(.+?)(?<=[^\\s*])\\*(?!\\*)") to "em",
    Regex("~~(?=\\S)(.+?)(?<=\\S)~~") to "strike",
    Regex("==(?=\\S)(.+?)(?<=\\S)==") to "mark",
    Regex("!?\\[([^\\]\\n]*)]\\(([^)\\s]*)(?: \"[^\"]*\")?\\)") to "link",
)

/** Character ranges [start, end) of fenced code blocks, fences included. */
fun codeBlocks(text: String): List<IntRange> {
    val out = mutableListOf<IntRange>()
    var start = -1
    var marker = ""
    var pos = 0
    for (line in text.split('\n')) {
        val m = fence.find(line)
        if (start < 0 && m != null) {
            start = pos
            marker = m.groupValues[1]
        } else if (start >= 0 && m != null && m.groupValues[1].startsWith(marker[0]) && m.groupValues[1].length >= marker.length && line.trim().all { it == marker[0] }) {
            out += start until pos + line.length
            start = -1
        }
        pos += line.length + 1
    }
    if (start >= 0) out += start until text.length
    return out
}

class Highlight(private val p: Palette, private val tint: Tint) {
    private val mk = SpanStyle(color = tint.deep, fontWeight = FontWeight.Medium)
    private val mono = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.5.sp)

    fun annotate(text: String): AnnotatedString = buildAnnotatedString {
        append(text)
        val blocks = codeBlocks(text)
        var pos = 0
        for (line in text.split('\n')) {
            val end = pos + line.length
            val block = blocks.firstOrNull { pos in it }
            if (block != null) {
                addStyle(mono.copy(fontSize = 13.5.sp), pos, end)
                if (fence.containsMatchIn(line)) addStyle(mk, pos, end)
            } else {
                line(this, line, pos)
            }
            pos = end + 1
        }
    }

    private fun line(b: AnnotatedString.Builder, line: String, at: Int) {
        var body = 0
        heading.find(line)?.let { h ->
            val level = h.groupValues[1].length
            val size = when (level) { 1 -> 24.sp; 2 -> 21.sp; 3 -> 18.sp; else -> 17.sp }
            b.addStyle(SpanStyle(fontSize = size, fontWeight = if (level == 1) FontWeight.Bold else FontWeight.SemiBold), at, at + line.length)
            b.addStyle(mk, at, at + h.range.last + 1)
            body = h.range.last + 1
        }
        if (rule.matches(line)) {
            b.addStyle(mk, at, at + line.length)
            return
        }
        if (tableRow.matches(line)) {
            b.addStyle(mono, at, at + line.length)
            line.forEachIndexed { i, c -> if (c == '|' || (c == '-' && line.trim().all { it in "|-: " })) b.addStyle(mk, at + i, at + i + 1) }
            return
        }
        quote.find(line)?.let { q ->
            b.addStyle(mk, at, at + q.range.last + 1)
            body = maxOf(body, q.range.last + 1)
        }
        listItem.find(line, 0)?.takeIf { body == 0 || it.range.first >= body }?.let { l ->
            b.addStyle(mk, at + l.groups[2]!!.range.first, at + l.range.last + 1)
            body = l.range.last + 1
        }
        inlineSpans(b, line, at, body)
    }

    private fun inlineSpans(b: AnnotatedString.Builder, line: String, at: Int, from: Int) {
        // Code spans first: nothing inside them is markup.
        val code = mutableListOf<IntRange>()
        Regex("(`+)(.+?)\\1").findAll(line, from).forEach { m ->
            code += m.range
            b.addStyle(mono.copy(background = p.code), at + m.range.first, at + m.range.last + 1)
            val n = m.groupValues[1].length
            b.addStyle(mk, at + m.range.first, at + m.range.first + n)
            b.addStyle(mk, at + m.range.last + 1 - n, at + m.range.last + 1)
        }
        fun free(r: IntRange) = code.none { it.first <= r.last && r.first <= it.last }
        for ((re, kind) in inline) {
            if (kind == "code") continue
            for (m in re.findAll(line, from)) {
                if (!free(m.range)) continue
                val s = at + m.range.first
                val e = at + m.range.last + 1
                when (kind) {
                    "link" -> {
                        val textEnd = s + line.indexOf("](", m.range.first) - m.range.first
                        val img = line[m.range.first] == '!'
                        b.addStyle(mk, s, s + if (img) 2 else 1)
                        if (!img) b.addStyle(SpanStyle(color = p.accent), s + 1, textEnd)
                        b.addStyle(mk, textEnd, textEnd + 2)
                        b.addStyle(SpanStyle(color = p.ink2), textEnd + 2, e - 1)
                        b.addStyle(mk, e - 1, e)
                    }
                    else -> {
                        val n = when (kind) { "em" -> 1; else -> 2 }
                        val style = when (kind) {
                            "strong" -> SpanStyle(fontWeight = FontWeight.SemiBold)
                            "em" -> SpanStyle(fontStyle = FontStyle.Italic)
                            "strike" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                            else -> SpanStyle(background = tint.mark)
                        }
                        b.addStyle(style, s, e)
                        b.addStyle(mk, s, s + n)
                        b.addStyle(mk, e - n, e)
                    }
                }
            }
        }
    }
}

/**
 * Colours the source and shows what is being heard at [at], faint (struck
 * through while cancelling), without it being part of the text.
 */
class SourceTransformation(
    private val hl: Highlight,
    private val interim: String?,
    private val at: Int,
    private val interimStyle: SpanStyle,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val coloured = hl.annotate(text.text)
        val extra = interim.orEmpty()
        if (extra.isEmpty()) return TransformedText(coloured, OffsetMapping.Identity)
        val a = at.coerceIn(0, text.length)
        val out = buildAnnotatedString {
            append(coloured.subSequence(0, a))
            pushStyle(interimStyle)
            append(extra)
            pop()
            append(coloured.subSequence(a, coloured.length))
        }
        val n = extra.length
        return TransformedText(out, object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = if (offset <= a) offset else offset + n
            override fun transformedToOriginal(offset: Int) = when {
                offset <= a -> offset
                offset < a + n -> a
                else -> offset - n
            }
        })
    }

    override fun equals(other: Any?) = other is SourceTransformation && other.interim == interim && other.at == at && other.hl === hl && other.interimStyle == interimStyle
    override fun hashCode() = (interim?.hashCode() ?: 0) * 31 + at
}

/** Enter in a list item continues the list; on an empty item it ends the list. */
fun continueList(old: String, new: String, cursor: Int): Pair<String, Int>? {
    if (new.length != old.length + 1 || cursor < 1 || new[cursor - 1] != '\n') return null
    if (old.substring(0, cursor - 1) != new.substring(0, cursor - 1)) return null
    val lineStart = new.lastIndexOf('\n', cursor - 2) + 1
    val line = new.substring(lineStart, cursor - 1)
    if (codeBlocks(old).any { cursor - 1 in it }) return null
    val m = listItem.find(line) ?: return null
    if (m.range.first != 0) return null
    val rest = line.substring(m.range.last + 1)
    // Right before an item's text: the text moves down as a new item and an
    // empty one stays above, as on the web (editor/lists.ts splitAtItemStart).
    val after = new.substring(cursor).substringBefore('\n')
    if (rest.isBlank() && after.isBlank()) {
        // An empty item: drop its marker and end the list.
        val s = new.substring(0, lineStart) + new.substring(cursor)
        return s to lineStart
    }
    val indent = m.groupValues[1]
    val marker = m.groupValues[2].let { mk ->
        mk.dropLast(1).toIntOrNull()?.let { "${it + 1}${mk.last()}" } ?: mk
    }
    val box = if (m.groupValues[3].isNotEmpty()) " [ ]" else ""
    val insert = "$indent$marker$box "
    return new.substring(0, cursor) + insert + new.substring(cursor) to cursor + insert.length
}

