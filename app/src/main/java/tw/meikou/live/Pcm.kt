package tw.meikou.live

import java.nio.ByteBuffer

/** Preserve the byte count and timestamps of a muted microphone block. */
internal fun silencePcm(buffer:ByteBuffer,count:Int){
    require(count in 0..buffer.capacity())
    for(index in 0 until count)buffer.put(index,0)
}

/** Combine signed, native-endian 16-bit PCM without wrapping a loud peak. */
internal fun mixPcm(mic:ByteBuffer,other:ByteBuffer,count:Int){
    require(count>=0 && count%2==0 && count<=minOf(mic.capacity(),other.capacity()))
    mic.order(java.nio.ByteOrder.nativeOrder());other.order(java.nio.ByteOrder.nativeOrder())
    for(i in 0 until count step 2){
        val summed=mic.getShort(i).toInt()+other.getShort(i).toInt()
        mic.putShort(i,summed.coerceIn(-32768,32767).toShort())
    }
}
