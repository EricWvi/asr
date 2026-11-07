package top.onlyquant.tingxie.ui

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.HighlightDelimiterParser
import org.intellij.markdown.flavours.gfm.StrikeThroughDelimiterParser
import org.intellij.markdown.parser.sequentialparsers.DelimiterParser
import org.intellij.markdown.parser.sequentialparsers.EmphasisLikeParser
import org.intellij.markdown.parser.sequentialparsers.SequentialParser
import org.intellij.markdown.parser.sequentialparsers.SequentialParserManager
import org.intellij.markdown.parser.sequentialparsers.TokensCache
import org.intellij.markdown.parser.sequentialparsers.impl.EmphStrongDelimiterParser

private val CJK = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}\\p{IsBopomofo}\\u3000-\\u303f\\uff00-\\uffef]")

private fun cjk(c: Char) = CJK.matches(c.toString())

/**
 * GFM whose emphasis, strikethrough and ==highlight== close the way the web
 * editor's do (frontend/src/editor/syntax.ts, after markdown-cjk-friendly):
 * punctuation on one side of a delimiter run may also face a CJK character
 * on the other, so `**加粗。**有` and `**哨兵（sentinel）**解决` are bold.
 */
class CjkFlavour : GFMFlavourDescriptor() {
    override val sequentialParserManager: SequentialParserManager = object : SequentialParserManager() {
        private val base = super@CjkFlavour.sequentialParserManager.getParserSequence()
        override fun getParserSequence(): List<SequentialParser> = base.map {
            if (it is EmphasisLikeParser) {
                EmphasisLikeParser(
                    CjkFriendly(EmphStrongDelimiterParser()),
                    CjkFriendly(StrikeThroughDelimiterParser()),
                    CjkFriendly(HighlightDelimiterParser()),
                )
            } else it
        }
    }
}

/**
 * A built-in delimiter parser (they are final) whose runs get their
 * open/close sides recomputed with CJK-friendly flanking.
 */
private class CjkFriendly(private val inner: DelimiterParser) : DelimiterParser() {
    override fun scan(tokens: TokensCache, iterator: TokensCache.Iterator, delimiters: MutableList<Info>): Int {
        val from = delimiters.size
        val n = inner.scan(tokens, iterator, delimiters)
        if (delimiters.size > from) {
            val left = tokens.Iterator(delimiters[from].position)
            val right = tokens.Iterator(delimiters.last().position)
            // Only `_` may not open or close inside a word.
            val (open, close) = canOpenClose(tokens, left, right, delimiters[from].marker != '_')
            for (i in from until delimiters.size) {
                delimiters[i].canOpen = open
                delimiters[i].canClose = close
            }
        }
        return n
    }

    override fun process(
        tokens: TokensCache,
        iterator: TokensCache.Iterator,
        delimiters: MutableList<Info>,
        result: SequentialParser.ParsingResultBuilder,
    ) = inner.process(tokens, iterator, delimiters, result)

    override fun isLeftFlankingRun(leftIt: TokensCache.Iterator, rightIt: TokensCache.Iterator) =
        !isWhitespace(rightIt, 1) &&
            (!isPunctuation(rightIt, 1) || isWhitespace(leftIt, -1) || isPunctuation(leftIt, -1) || cjk(leftIt.charLookup(-1)))

    override fun isRightFlankingRun(tokens: TokensCache, leftIt: TokensCache.Iterator, rightIt: TokensCache.Iterator) =
        leftIt.charLookup(-1) != leftIt.firstChar &&
            !isWhitespace(leftIt, -1) &&
            (!isPunctuation(leftIt, -1) || isWhitespace(rightIt, 1) || isPunctuation(rightIt, 1) || cjk(rightIt.charLookup(1)))
}
