package top.onlyquant.tingxie.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TidyTest {
    private fun span(s: String, part: String, from: Int = 0): List<Int> {
        val a = s.indexOf(part, from)
        return listOf(a, a + part.length)
    }

    @Test fun spansApartOnlyByBlanksFormOneSegment() {
        val s = "手写\n\n嗯第一句\n\n然后第二句"
        val segs = segmentsOf(s, listOf(span(s, "嗯第一句"), span(s, "然后第二句")))
        assertEquals(1, segs.size)
        assertEquals("嗯第一句\n\n然后第二句", segs[0].text)
    }

    @Test fun handwritingBetweenSplitsSegments() {
        val s = "嗯第一句 手写 然后第二句"
        val segs = segmentsOf(s, listOf(span(s, "嗯第一句"), span(s, "然后第二句")))
        assertEquals(listOf("嗯第一句", "然后第二句"), segs.map { it.text })
    }

    @Test fun prefixAndSuffixOnTheLine() {
        val s = "- 买菜那个就是牛奶 明天\n下一行"
        val seg = segmentsOf(s, listOf(span(s, "买菜那个就是牛奶")))[0]
        assertEquals("- ", seg.prefix)
        assertEquals(" 明天", seg.suffix)
        val alone = "嗯这个\n"
        assertEquals("", segmentsOf(alone, listOf(span(alone, "嗯这个")))[0].prefix)
    }

    @Test fun headingsSkipFencedCode() {
        val s = "# 一\n```\n# 不算\n```\n## 二\n正文"
        val seg = segmentsOf(s, listOf(span(s, "正文")))[0]
        assertEquals(listOf("# 一", "## 二"), seg.headings)
    }

    @Test fun cleaningDropsFenceAndJoinsInlineLines() {
        assertEquals("a\nb", cleanTidied("```markdown\na\nb\n```\n", false))
        assertEquals("ab", cleanTidied(" a\n b ", true))
    }

    @Test fun replacingShiftsLaterTextAndDropsTheMarks() {
        val s = "嗯一 手写 嗯二 尾巴"
        val spans = listOf(span(s, "嗯一"), span(s, "嗯二"), span(s, "尾巴"))
        val a = span(s, "嗯一")
        val b = span(s, "嗯二")
        val r = replaceSegments(s, spans, listOf(Replacement(b[0], b[1], "二"), Replacement(a[0], a[1], "第一")))
        assertEquals("第一 手写 二 尾巴", r.content)
        assertEquals(listOf(span(r.content, "尾巴")), r.spans)
        assertEquals(listOf(0 to 2, 6 to 7), r.placed)
    }
}
