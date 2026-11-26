package tw.meikou.live

import kotlin.math.abs

fun main() {
    val out = FloatArray(16)
    var tested = 0
    for (swap in listOf(false, true)) for (x in listOf(-1f, 1f)) for (y in listOf(-1f, 1f)) {
        val input = FloatArray(16)
        input[if (swap) 4 else 0] = x * .8f
        input[if (swap) 1 else 5] = y * .6f
        input[12] = if (x < 0) .9f else .1f
        input[13] = if (y < 0) .8f else .2f
        CameraGeometry.cameraCrop(input, out)
        check(abs(out[12] - .1f) < .0001f && abs(out[13] - .8f) < .0001f)
        check(out[0] == .8f && out[5] == -.6f && out[4] == 0f && out[1] == 0f)
        tested++
    }
    val viewport = IntArray(4)
    for ((srcW, srcH) in listOf(1920 to 1080, 1080 to 1920)) {
        for ((w,h) in listOf(1182 to 674, 1080 to 1920, 1920 to 1080, 800 to 800)) {
            CameraGeometry.fit(srcW, srcH, w, h, viewport)
            val (left,bottom,width,height) = viewport
            check(width == w || height == h)
            check(abs(2*left + width - w) <= 1 && abs(2*bottom + height - h) <= 1)
            check(abs(width.toDouble()*srcH/srcW - height) <= 1)
            tested++
        }
    }
    println("PASS: $tested camera crop and viewport cases")
}
