package tw.meikou.live

import java.nio.ByteBuffer

fun main() {
    val block = ByteBuffer.allocateDirect(2048)
    for (i in 0 until block.capacity()) block.put(i, 73)
    block.position(12)
    silencePcm(block, 1920)
    check(block.position() == 12)
    for (i in 0 until 1920) check(block.get(i).toInt() == 0)
    for (i in 1920 until block.capacity()) check(block.get(i).toInt() == 73)
    silencePcm(block, 0)
    check(runCatching { silencePcm(block, 2049) }.isFailure)
    println("PCM direct buffer mute: pass")
}
