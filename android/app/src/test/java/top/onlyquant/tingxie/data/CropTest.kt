package top.onlyquant.tingxie.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CropTest {
    private val eps = 1e-3f

    // A 2000×1000 picture in a 400×600 stage under a frame of radius 150.
    private val v = CropView(2000, 1000, 400f, 600f, 150f)

    @Test fun startsCentredWithTheShortSideFillingTheFrame() {
        val (l, t, s) = v.source()
        assertEquals(1000f, s, eps)
        assertEquals(500f, l, eps)
        assertEquals(0f, t, eps)
    }

    @Test fun panningStopsAtTheFrameEdge() {
        val (l, t, _) = v.panBy(10_000f, 10_000f).source()
        assertEquals(0f, l, eps)
        assertEquals(0f, t, eps)
        val (l2, _, s2) = v.panBy(-10_000f, 0f).source()
        assertEquals(2000f, l2 + s2, eps)
    }

    @Test fun zoomKeepsTheCentreAndIsBounded() {
        val z = v.zoomTo(2f)
        val (l, t, s) = z.source()
        assertEquals(500f, s, eps)
        assertEquals(750f, l, eps)
        assertEquals(250f, t, eps)
        assertEquals(4f, v.zoomTo(9f).zoom, eps)
        assertEquals(1f, v.zoomTo(.2f).zoom, eps)
    }

    @Test fun zoomingOutAgainStillCoversTheFrame() {
        val (l, t, s) = v.zoomTo(4f, 0f, 0f).panBy(-10_000f, -10_000f).zoomTo(1f).source()
        assertEquals(1000f, s, eps)
        assertEquals(2000f, l + s, eps)
        assertEquals(0f, t, eps)
    }
}
