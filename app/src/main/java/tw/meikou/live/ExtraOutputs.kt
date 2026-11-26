package tw.meikou.live

import java.io.Closeable
import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Each additional destination owns an independent bounded queue and socket. */
class ExtraOutputs(urls:List<URI>,private val status:(Int,String)->Unit):Closeable{
    private data class Packet(val type:Int,val ms:Int,val bytes:ByteArray)
    private class Output(val url:URI,val index:Int){
        val queue=ArrayBlockingQueue<Packet>(72)
        val closed=AtomicBoolean()
        val sender=Rtmp(url)
        fun close(){if(closed.compareAndSet(false,true)){sender.close();queue.clear()}}
    }
    private val outputs=urls.mapIndexed{i,url->Output(url,i+2)}
    fun start(){outputs.forEach{out->
        thread(name="Output "+out.index,isDaemon=true){
            try{
                status(out.index,"連線中");out.sender.connect()
                var hasVideo=false;var hasAudio=false;var reporting=false
                while(!out.closed.get()){
                    val p=out.queue.poll(300,java.util.concurrent.TimeUnit.MILLISECONDS)?:continue
                    out.sender.media(p.type,p.ms,p.bytes)
                    if(p.bytes.getOrNull(1)?.toInt()==1){
                        if(p.type==8)hasAudio=true else if(p.type==9)hasVideo=true
                    }
                    if(!reporting && hasVideo && hasAudio){reporting=true;status(out.index,"已送出影音")}
                }
            }catch(e:Exception){if(!out.closed.get())status(out.index,"連線失敗")}
            finally{out.close()}
        }
    }}
    fun send(type:Int,ms:Int,bytes:ByteArray){
        val p=Packet(type,ms,bytes)
        outputs.forEach{out->
            if(!out.closed.get() && !out.queue.offer(p)){
                out.close();status(out.index,"傳送逾時，已停止此路")
            }
        }
    }
    override fun close(){outputs.forEach{it.close()}}
}
