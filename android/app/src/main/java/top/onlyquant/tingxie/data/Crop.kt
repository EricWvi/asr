package top.onlyquant.tingxie.data

/**
 * A picture placed under the round crop frame (.crop-stage and .crop-ring):
 * a [w]×[h] image drawn at ([x], [y]) in a [stageW]×[stageH] stage, scaled
 * so that its short side fits the frame at zoom 1. The frame, of radius
 * [r], sits in the middle of the stage and the image always covers it.
 */
data class CropView(
    val w: Int,
    val h: Int,
    val stageW: Float,
    val stageH: Float,
    val r: Float,
    val zoom: Float = 1f,
    val x: Float = (stageW - w * 2 * r / minOf(w, h)) / 2,
    val y: Float = (stageH - h * 2 * r / minOf(w, h)) / 2,
) {
    /** Stage pixels per image pixel. */
    val scale: Float get() = 2 * r / minOf(w, h) * zoom

    /** Moved by ([dx], [dy]), still covering the frame. */
    fun panBy(dx: Float, dy: Float) = copy(x = x + dx, y = y + dy).clamped()

    /** Zoomed to [z] (1–4) keeping the point ([cx], [cy]) of the stage where it is. */
    fun zoomTo(z: Float, cx: Float = stageW / 2, cy: Float = stageH / 2): CropView {
        val k0 = scale
        val next = copy(zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM))
        val k1 = next.scale
        return next.copy(x = cx - (cx - x) * k1 / k0, y = cy - (cy - y) * k1 / k0).clamped()
    }

    private fun clamped(): CropView {
        val k = scale
        val cx = stageW / 2
        val cy = stageH / 2
        return copy(
            x = x.coerceIn(cx + r - w * k, cx - r),
            y = y.coerceIn(cy + r - h * k, cy - r),
        )
    }

    /** The part of the image inside the frame: left, top and side, in image pixels. */
    fun source(): Triple<Float, Float, Float> {
        val k = scale
        return Triple((stageW / 2 - r - x) / k, (stageH / 2 - r - y) / k, 2 * r / k)
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 4f
    }
}
