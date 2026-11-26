package tw.meikou.live

import java.nio.ByteBuffer

/** Preserve the byte count and timestamps of a muted microphone block. */
internal fun silencePcm(buffer:ByteBuffer,count:Int){
    require(count in 0..buffer.capacity())
    for(index in 0 until count)buffer.put(index,0)
}
