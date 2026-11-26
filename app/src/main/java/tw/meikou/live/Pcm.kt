package tw.meikou.live

import java.nio.ByteBuffer

/**
 * Streaming linear resampler. Output sample m is placed at the exact rational source
 * position m * inputRate / outputRate, so buffer boundaries cannot alter pitch.
 */
internal class PcmResampler(private val inputRate:Int,private val outputRate:Int=48000){
    private var initialized=false
    private var previous=0
    private var inputIndex=0L
    private var nextOutputNumerator=0L
    init{require(inputRate>0 && outputRate>0)}
    fun accept(sample:Int,emit:(Int)->Unit){
        if(!initialized){
            emit(sample)
            previous=sample
            inputIndex=1
            nextOutputNumerator=inputRate.toLong()
            initialized=true
            return
        }
        val start=(inputIndex-1)*outputRate
        val end=inputIndex*outputRate
        while(nextOutputNumerator<=end){
            val fraction=nextOutputNumerator-start
            val value=previous.toLong()+(sample.toLong()-previous)*fraction/outputRate
            emit(value.toInt())
            nextOutputNumerator+=inputRate
        }
        previous=sample
        inputIndex++
    }
}

/** Preserve the byte count and timestamps of a muted microphone block. */
internal fun silencePcm(buffer:ByteBuffer,count:Int){
    require(count in 0..buffer.capacity())
    for(index in 0 until count)buffer.put(index,0)
}

/** Apply a live mixer level without changing the block length or wrapping loud samples. */
internal fun scalePcm(buffer:ByteBuffer,count:Int,level:Int){
    require(count>=0 && count%2==0 && count<=buffer.capacity())
    require(level in 0..200)
    buffer.order(java.nio.ByteOrder.nativeOrder())
    for(i in 0 until count step 2){
        val scaled=buffer.getShort(i).toInt()*level/100
        buffer.putShort(i,scaled.coerceIn(-32768,32767).toShort())
    }
}

/** Combine signed, native-endian 16-bit PCM without wrapping a loud peak. */
internal fun mixPcm(mic:ByteBuffer,other:ByteBuffer,count:Int,otherLevel:Int=100){
    require(count>=0 && count%2==0 && count<=minOf(mic.capacity(),other.capacity()))
    require(otherLevel in 0..200)
    mic.order(java.nio.ByteOrder.nativeOrder());other.order(java.nio.ByteOrder.nativeOrder())
    for(i in 0 until count step 2){
        val summed=mic.getShort(i).toInt()+other.getShort(i).toInt()*otherLevel/100
        mic.putShort(i,summed.coerceIn(-32768,32767).toShort())
    }
}
