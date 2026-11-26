package tw.meikou.live

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

internal object CameraGeometry {
    // Camera2 tags each buffer with a crop, quarter-turn and optional mirror.
    // Retain the sampled rectangle, then apply the selected lens and display
    // orientation exactly once. Raw camera pixels use top-left row order.
    fun cameraCrop(producer: FloatArray, out: FloatArray) {
        out.fill(0f)
        out[0] = abs(producer[0]) + abs(producer[4])
        out[5] = -(abs(producer[1]) + abs(producer[5]))
        out[12] = producer[12] + min(0f, producer[0]) + min(0f, producer[4])
        out[13] = producer[13] + maxOf(0f, producer[1]) + maxOf(0f, producer[5])
        out[10] = 1f
        out[15] = 1f
    }

    fun fit(srcW: Int, srcH: Int, dstW: Int, dstH: Int, viewport: IntArray) {
        val scale = min(dstW.toDouble() / srcW, dstH.toDouble() / srcH)
        val w = (srcW * scale).roundToInt().coerceIn(1, dstW)
        val h = (srcH * scale).roundToInt().coerceIn(1, dstH)
        viewport[0] = (dstW - w) / 2
        viewport[1] = (dstH - h) / 2
        viewport[2] = w
        viewport[3] = h
    }
}
