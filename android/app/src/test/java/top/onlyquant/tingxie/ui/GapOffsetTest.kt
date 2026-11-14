package top.onlyquant.tingxie.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GapOffsetTest {
    // "a" at 0, blank lines starting at 2, 3 and 4, "b" at 5.
    private val text = "a\n\n\n\nb"

    @Test fun firstBlankLine() = assertEquals(2, gapOffset(text, 1, 5, 0f))

    @Test fun middleBlankLine() = assertEquals(3, gapOffset(text, 1, 5, .5f))

    @Test fun lastBlankLine() = assertEquals(4, gapOffset(text, 1, 5, .99f))

    @Test fun blankLinesAtTheTop() = assertEquals(1, gapOffset("\n\n\nb", null, 3, .5f))

    @Test fun firstLineOfTheText() = assertEquals(0, gapOffset("\n\nb", null, 2, 0f))

    @Test fun noBlankLineGoesToTheEndAbove() = assertEquals(1, gapOffset("a\nb", 1, 2, .5f))

    @Test fun belowTheLastBlock() = assertEquals(3, gapOffset("a\n\n", 1, null, 1f))

    @Test fun belowTheLastLine() = assertEquals(1, gapOffset("a", 1, null, 1f))

    @Test fun listItems() = assertEquals(7, gapOffset("- [ ] a\n- [ ] b", 7, 14, .5f))

    @Test fun spacesOnlyLineIsBlank() = assertEquals(2, gapOffset("a\n  \nb", 1, 5, .5f))
}
