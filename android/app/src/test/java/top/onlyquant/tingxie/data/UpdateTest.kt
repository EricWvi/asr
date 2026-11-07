package top.onlyquant.tingxie.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateTest {
    @Test fun readsTheManifestTheWorkflowWrites() {
        val app = json.decodeFromString<LatestApp>(
            """{"version":"0.6.0","version_code":6000,"url":"https://x/a.apk","sha256":"ab","size":12}""",
        )
        assertEquals(LatestApp("0.6.0", 6000, "https://x/a.apk", "ab", 12), app)
    }

    @Test fun checksAtMostOnceADay() {
        val day = UPDATE_CHECK_EVERY_MS
        assertTrue(updateCheckDue(0, day * 100))
        assertFalse(updateCheckDue(day, day + 1))
        assertTrue(updateCheckDue(day, day * 2))
    }

    @Test fun aClockSetBackDoesNotStopChecks() {
        assertTrue(updateCheckDue(1000, 500))
    }

    @Test fun percentStaysInRange() {
        assertEquals(42, percentOf(42, 100))
        assertEquals(0, percentOf(5, 0))
        assertEquals(100, percentOf(200, 100))
    }

    @Test fun hashesAFile() {
        val f = File.createTempFile("sha", null).apply { writeText("abc"); deleteOnExit() }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Of(f))
    }
}
