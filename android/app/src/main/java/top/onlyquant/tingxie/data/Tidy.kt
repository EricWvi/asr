package top.onlyquant.tingxie.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/*
 * Tidying, as the web does it (frontend/src/editor/tidy.ts and
 * voice/tidy.ts): marked spans next to each other, or apart only by blank
 * space, form a segment; every segment goes to the model at once, and once
 * all have answered the results replace them in one change. The text stays
 * editable meanwhile; a segment edited by hand in the meantime keeps its
 * text and loses its result.
 */

/** One piece sent to the model, as it was when the tidy started. */
data class Segment(
    val from: Int,
    val to: Int,
    val text: String,
    /** Heading lines above the segment, as context for new headings. */
    val headings: List<String>,
    /** Text before and after the segment on its first and last lines. */
    val prefix: String,
    val suffix: String,
)

fun segmentsOf(content: String, spans: List<Span>): List<Segment> {
    val groups = mutableListOf<MutableList<Span>>()
    for (s in spans.fitting(content)) {
        val last = groups.lastOrNull()
        if (last != null && content.substring(last.last()[1], s[0]).isBlank()) last += s else groups += mutableListOf(s)
    }
    return groups.map { g ->
        val from = g.first()[0]
        val to = g.last()[1]
        val lineStart = content.lastIndexOf('\n', from - 1) + 1
        val lineEnd = content.indexOf('\n', to).let { if (it < 0) content.length else it }
        val prefix = content.substring(lineStart, from)
        val suffix = content.substring(to, lineEnd)
        Segment(
            from, to, content.substring(from, to),
            headings = headingsBefore(content, lineStart),
            prefix = if (prefix.isBlank()) "" else prefix,
            suffix = if (suffix.isBlank()) "" else suffix,
        )
    }
}

private val fenceLine = Regex("^\\s{0,3}(`{3,}|~{3,})")
private val headingLine = Regex("^\\s{0,3}#{1,6}\\s")

/** ATX headings on the lines before [end], skipping fenced code. */
fun headingsBefore(content: String, end: Int): List<String> {
    if (end <= 0) return emptyList()
    val out = mutableListOf<String>()
    var fence: Char? = null
    for (line in content.substring(0, end).removeSuffix("\n").split('\n')) {
        val open = fenceLine.find(line)?.groupValues?.get(1)
        if (open != null) {
            fence = if (fence == null) open[0] else if (open[0] == fence) null else fence
            continue
        }
        if (fence == null && headingLine.containsMatchIn(line)) out += line.trim()
    }
    return out
}

private val fenced = Regex("^```(?:markdown|md)?\\n([\\s\\S]*)\\n```$")

/**
 * The model's answer as it goes into the text: trimmed, without a code
 * fence around it, and on one line where it sits inside a line.
 */
fun cleanTidied(text: String, inline: Boolean): String {
    var t = text.trim()
    fenced.find(t)?.let { t = it.groupValues[1].trim() }
    return if (inline) t.replace(Regex("\\s*\\n\\s*"), "") else t
}

/** Tidied text for [from, to) of the text it replaces. */
data class Replacement(val from: Int, val to: Int, val text: String)

/** Text and spans after the replacements, and where each new text landed. */
data class Replaced(val content: String, val spans: List<Span>, val placed: List<Pair<Int, Int>>)

/** Writes the replacements; spans inside the replaced text go with it. */
fun replaceSegments(content: String, spans: List<Span>, results: List<Replacement>): Replaced {
    var text = content
    var marks = spans.fitting(content)
    var shift = 0
    val placed = mutableListOf<Pair<Int, Int>>()
    for (r in results.sortedBy { it.from }) {
        val a = r.from + shift
        val b = r.to + shift
        text = text.substring(0, a) + r.text + text.substring(b)
        marks = marks.mapped(Change(a, b, r.text.length), manual = false)
        placed += a to a + r.text.length
        shift += r.text.length - (r.to - r.from)
    }
    return Replaced(text, marks, placed)
}

/**
 * Runs tidies for the open document. [busyWith] is the document being
 * tidied, if any; talking is off meanwhile. Library reports every change
 * to the text through [onChange], which keeps the segments in place.
 */
class Tidier(
    private val scope: CoroutineScope,
    private val library: () -> Library,
    private val api: () -> Api?,
    private val toast: (String, String?, (() -> Unit)?) -> Unit,
    /** Takes back the undo offer, if it is still showing. */
    private val withdraw: () -> Unit = {},
) {
    var busyWith by mutableStateOf<Long?>(null)
        private set

    /** Where the tidied text landed, for its fading underline; empty after an undo. */
    var onPlaced: (Long, List<Pair<Int, Int>>) -> Unit = { _, _ -> }

    private class Live(val seg: Segment, var from: Int, var to: Int, var touched: Boolean = false)

    private var live: List<Live> = emptyList()
    private var job: Job? = null

    /** The document whose last tidy can still be undone. */
    private var undoable: Long? = null

    fun onChange(id: Long, c: Change) {
        // Any change after a tidy ends the chance to undo it.
        if (id == undoable) {
            undoable = null
            withdraw()
        }
        if (id != busyWith) return
        for (l in live) {
            if (l.touched) continue
            if (c.reachesInside(l.from, l.to)) {
                l.touched = true
            } else {
                l.from = c.map(l.from, 1)
                l.to = c.map(l.to, -1)
            }
        }
    }

    /** Tap on 整理: starts, or cancels the running tidy. */
    fun toggle(id: Long) {
        if (busyWith != null) {
            cancel()
            return
        }
        val lib = library()
        val d = lib.doc(id) ?: return
        val segs = segmentsOf(d.content, d.tidyRanges)
        if (segs.isEmpty()) {
            toast("没有待整理的文字", null, null)
            return
        }
        val a = api() ?: return
        val tracked = segs.map { Live(it, it.from, it.to) }
        live = tracked
        busyWith = id
        job = scope.launch {
            val outcomes = segs.map { s ->
                async {
                    try {
                        Result.success(cleanTidied(a.tidy(TidyRequest(s.text, s.headings, s.prefix, s.suffix)), s.prefix.isNotEmpty() || s.suffix.isNotEmpty()))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
            }.awaitAll()
            ensureActive()
            busyWith = null
            live = emptyList()
            job = null
            finish(id, tracked, outcomes)
        }
    }

    /** Stops a running tidy; nothing is replaced. */
    fun cancel() {
        job?.cancel()
        job = null
        busyWith = null
        live = emptyList()
    }

    private fun finish(id: Long, tracked: List<Live>, outcomes: List<Result<String>>) {
        val lib = library()
        val before = lib.doc(id) ?: return
        val results = mutableListOf<Replacement>()
        var failure: Throwable? = null
        var edited = 0
        for ((l, o) in tracked.zip(outcomes)) {
            val text = o.getOrElse {
                if (failure == null) failure = it
                null
            } ?: continue
            val intact = !l.touched && l.to <= before.content.length && before.content.substring(l.from, l.to) == l.seg.text
            if (intact && text.isNotEmpty()) results += Replacement(l.from, l.to, text) else edited++
        }
        val note = failure?.let { it.tidyMessage() } ?: if (edited > 0) "整理期间文字被改动，改动的部分没有替换" else null
        if (results.isEmpty()) {
            note?.let { toast(it, null, null) }
            return
        }
        val spans = before.tidyRanges.fitting(before.content)
        val r = replaceSegments(before.content, spans, results)
        lib.edit(id, DocPatch(content = r.content, tidyRanges = r.spans))
        onPlaced(id, r.placed)
        undoable = id
        toast(note ?: "已整理", "撤销") {
            if (undoable != id || lib.doc(id)?.content != r.content) return@toast
            undoable = null
            lib.edit(id, DocPatch(content = before.content, tidyRanges = spans))
            onPlaced(id, emptyList())
        }
    }
}

private fun Throwable.tidyMessage() = if (this is ApiError) message ?: "整理失败，请稍后再试" else "整理失败，请稍后再试"
