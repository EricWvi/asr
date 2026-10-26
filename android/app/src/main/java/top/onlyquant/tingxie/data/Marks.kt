package top.onlyquant.tingxie.data

/*
 * Dictated text awaiting tidying, as frontend/src/editor/tidy.ts keeps it:
 * [from, to) spans in UTF-16 units, in order, never overlapping. Spans
 * follow edits; an edit by hand that reaches inside a span drops it, while
 * typing right at its edges does not. Dictation into a span stays part of it.
 */

typealias Span = List<Int>

/** One change: [fromA, toA) of the old text became [inserted] characters. */
data class Change(val fromA: Int, val toA: Int, val inserted: Int) {
    /** Where an old position lands; assoc < 0 keeps it before inserted text, > 0 after. */
    fun map(pos: Int, assoc: Int): Int = when {
        pos < fromA -> pos
        pos > toA -> pos + inserted - (toA - fromA)
        fromA == toA -> if (assoc < 0) pos else pos + inserted
        else -> if (assoc < 0) fromA else fromA + inserted
    }

    /** Whether it reaches inside [from, to); touching the edges does not. */
    fun reachesInside(from: Int, to: Int) =
        if (fromA == toA) fromA in (from + 1) until to else fromA < to && toA > from

    companion object {
        /** The single change that turns [old] into [new]: common prefix and suffix kept. */
        fun between(old: String, new: String): Change? {
            if (old == new) return null
            var start = 0
            val max = minOf(old.length, new.length)
            while (start < max && old[start] == new[start]) start++
            var end = 0
            while (end < max - start && old[old.length - 1 - end] == new[new.length - 1 - end]) end++
            return Change(start, old.length - end, new.length - end - start)
        }
    }
}

/** Spans after [c]; [manual] edits drop the spans they reach into. */
fun List<Span>.mapped(c: Change, manual: Boolean): List<Span> = mapNotNull { (from, to) ->
    if (manual && c.reachesInside(from, to)) return@mapNotNull null
    val a = c.map(from, 1)
    val b = c.map(to, -1)
    if (a < b) listOf(a, b) else null
}

/** Adds a dictated span unless a marked stretch already covers it. */
fun List<Span>.plus(from: Int, to: Int): List<Span> {
    if (from >= to || any { (f, t) -> f < to && t > from }) return this
    return (this + listOf(listOf(from, to))).sortedBy { it[0] }
}

/** Only spans that fit [text]: ordered, non-overlapping, in bounds. */
fun List<Span>.fitting(text: String): List<Span> {
    var last = 0
    return filter { s ->
        val ok = s.size == 2 && s[0] >= last && s[0] < s[1] && s[1] <= text.length
        if (ok) last = s[1]
        ok
    }
}
