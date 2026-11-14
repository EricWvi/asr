package top.onlyquant.tingxie.ui

import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Test

class BlankLinesTest {
    private fun blocks(s: String): List<Pair<String, Int>> =
        blocks(MarkdownParser(CjkFlavour()).buildMarkdownTreeFromString(s).children)
            .map { (n, blanks) -> n.getTextInNode(s).toString() to blanks }

    @Test fun adjacentLinesAreOneBlock() = assertEquals(listOf("a\nb" to 0), blocks("a\nb"))

    @Test fun oneBlankLine() = assertEquals(listOf("a" to 0, "b" to 1), blocks("a\n\nb"))

    @Test fun manyBlankLinesKept() = assertEquals(listOf("a" to 0, "b" to 3), blocks("a\n\n\n\nb"))

    @Test fun spacesOnlyLineIsBlank() = assertEquals(listOf("a" to 0, "b" to 2), blocks("a\n  \n\nb"))

    @Test fun leadingBlankLines() = assertEquals(listOf("a" to 2), blocks("\n\na"))

    @Test fun afterHeading() = assertEquals(listOf("# h" to 0, "a" to 2), blocks("# h\n\n\na"))
}
