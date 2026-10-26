package top.onlyquant.tingxie.data

/*
 * The word count the web app shows (alfaaz countWords): every CJK, Thai, Lao,
 * Burmese, Khmer, Javanese or Vai character is a word, and other words are
 * runs between separators. Counted on UTF-16 units, like JavaScript.
 */

private val ranges = intArrayOf(
    3584, 3711, 3712, 3839, 4096, 4255, 6016, 6143, 43392, 43487, 42240, 42559,
    19968, 40959, 13312, 19903, 63744, 64255, 12032, 12255, 11904, 12031,
    12288, 12351, 13056, 13311, 65072, 65103,
)

private const val separators = " \n\t\u000B*/&:;.,?=\u0F0B\u1361\u200B"

private fun inRange(c: Int): Boolean {
    var i = 0
    while (i < ranges.size) {
        if (c >= ranges[i] && c <= ranges[i + 1]) return true
        i += 2
    }
    return false
}

fun countWords(s: String): Int {
    var count = 0
    var shouldCount = false
    for (ch in s) {
        val range = inRange(ch.code)
        val match = range || separators.indexOf(ch) >= 0
        if (match && (shouldCount || range)) count++
        shouldCount = !match
    }
    if (shouldCount) count++
    return count
}
