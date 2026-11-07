package top.onlyquant.tingxie.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueListTest {
    /** Presses Enter at `|` in [before] and returns the result with `|` at the cursor. */
    private fun enter(before: String): String? {
        val at = before.indexOf('|')
        val old = before.removeRange(at, at + 1)
        val new = old.substring(0, at) + "\n" + old.substring(at)
        val (s, c) = continueList(old, new, at + 1) ?: return null
        return s.substring(0, c) + "|" + s.substring(c)
    }

    @Test fun continuesAfterText() = assertEquals("- ab\n- |", enter("- ab|"))

    @Test fun splitsInsideText() = assertEquals("- ab\n- |cd", enter("- ab|cd"))

    @Test fun numbersTheNextItem() = assertEquals("1. ab\n2. |", enter("1. ab|"))

    @Test fun emptyItemEndsTheList() = assertEquals("a\n|", enter("a\n- |"))

    @Test fun beforeTextLeavesAnEmptyItemAbove() = assertEquals("- \n- |abc", enter("- |abc"))

    @Test fun beforeTextInOrderedItem() = assertEquals("1. \n2. |abc", enter("1. |abc"))

    @Test fun beforeTextInTask() = assertEquals("- [ ] \n- [ ] |abc", enter("- [x] |abc").let { it?.replaceFirst("[x]", "[ ]") })

    @Test fun plainTextIsLeftAlone() = assertNull(enter("ab|cd"))
}
