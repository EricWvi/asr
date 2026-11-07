package top.onlyquant.tingxie.ui

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Test

class EmphasisTest {
    private fun spans(s: String): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: ASTNode) {
            if (n.type == MarkdownElementTypes.STRONG || n.type == MarkdownElementTypes.EMPH || n.type == GFMElementTypes.STRIKETHROUGH) {
                out += n.getTextInNode(s).toString()
            }
            n.children.forEach(::walk)
        }
        walk(MarkdownParser(CjkFlavour()).buildMarkdownTreeFromString(s))
        return out
    }

    @Test fun boldAfterFullStop() = assertEquals(listOf("**加粗。**"), spans("**加粗。**有"))

    @Test fun boldAfterBracket() = assertEquals(listOf("**哨兵（sentinel）**"), spans("**哨兵（sentinel）**解决"))

    @Test fun strikeAfterFullStop() = assertEquals(listOf("~~删除。~~"), spans("~~删除。~~有"))

    @Test fun plainRulesStillHold() = assertEquals(emptyList<String>(), spans("a** b**"))

    @Test fun latinBoldUnchanged() = assertEquals(listOf("**bold**"), spans("x **bold** y"))

    @Test fun italicAfterFullStop() = assertEquals(listOf("*斜体。*"), spans("*斜体。*有"))
}
