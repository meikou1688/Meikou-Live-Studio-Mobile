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
    val left=ByteBuffer.allocateDirect(8).order(java.nio.ByteOrder.nativeOrder())
    val right=ByteBuffer.allocateDirect(8).order(java.nio.ByteOrder.nativeOrder())
    listOf(20000,-22000,200,-10).forEachIndexed{i,v->left.putShort(i*2,v.toShort());right.putShort(i*2,v.toShort())}
    mixPcm(left,right,6)
    check((0..3).map{left.getShort(it*2).toInt()}==listOf(32767,-32768,400,-10))
    println("PASS: PCM mute, mixing, peak clipping and partial buffers")
}
