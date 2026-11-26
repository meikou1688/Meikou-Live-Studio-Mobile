package tw.meikou.live

import android.media.MediaCodec
import android.media.MediaFormat
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.srt.srt.SrtClient
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class SrtOutput(private val uri:URI,private val hevc:Boolean) : ConnectChecker {
    private val client=SrtClient(this)
    private val connected=CountDownLatch(1)
    private val closed=AtomicBoolean()
    @Volatile private var online=false
    override fun onConnectionStarted(url:String){}
    override fun onConnectionSuccess(){online=true;connected.countDown()}
    override fun onConnectionFailed(reason:String){online=false;connected.countDown()}
    override fun onAuthError(){online=false;connected.countDown()}
    override fun onAuthSuccess(){}
    override fun onDisconnect(){online=false;connected.countDown()}
    fun connect(){
        client.setLogs(false)
        client.setCheckServerAlive(true)
        client.setVideoCodec(if(hevc)VideoCodec.H265 else VideoCodec.H264)
        client.setAudioInfo(48000,false)
        synchronized(this){if(closed.get())throw IOException();client.connect(uri.toASCIIString())}
        if(!connected.await(12,TimeUnit.SECONDS) || !online || closed.get())throw IOException()
    }
    fun configure(format:MediaFormat){
        val sets=(0..2).mapNotNull { format.getByteBuffer("csd-"+it)?.duplicate() }.flatMap {
            Avc.units(ByteArray(it.remaining()).also(it::get))
        }
        fun unit(type:Int):ByteBuffer {
            val data=sets.first { it.isNotEmpty() && (if(hevc)(it[0].toInt() ushr 1) and 63 else it[0].toInt() and 31)==type }
            return ByteBuffer.wrap(byteArrayOf(0,0,0,1)+data)
        }
        if(hevc)client.setVideoInfo(unit(33),unit(34),unit(32))
        else client.setVideoInfo(unit(7),unit(8),null)
    }
    fun send(audio:Boolean,data:ByteArray,pts:Long,flags:Int){
        if(!online || closed.get())throw IOException()
        val info=MediaCodec.BufferInfo().apply{set(0,data.size,pts,flags)}
        if(audio)client.sendAudio(ByteBuffer.wrap(data),info) else client.sendVideo(ByteBuffer.wrap(data),info)
    }
    fun sentBoth()=client.sentVideoFrames>0 && client.sentAudioFrames>0
    @Synchronized fun close(){
        if(closed.compareAndSet(false,true)){connected.countDown();client.disconnect()}
    }
}
