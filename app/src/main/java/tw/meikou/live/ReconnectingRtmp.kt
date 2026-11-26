package tw.meikou.live

import java.io.Closeable
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** A failed RTMP connection can be replaced without reopening the camera, microphone or MP4. */
internal class ReconnectingRtmp(private val target:URI,private val status:(Boolean)->Unit,
                                private val requestKey:()->Unit):Closeable {
    private class Session(val client:Rtmp,val resumed:Boolean=false){
        var epoch:Int?=null
        var audio=false
        var video=false
        var announced=false
    }
    private val active=AtomicReference<Session?>()
    private val pending=AtomicReference<Rtmp?>()
    private val closed=AtomicBoolean()
    @Volatile private var worker:Thread?=null
    private var audioHeader:ByteArray?=null
    private var videoHeader:ByteArray?=null

    fun connect(){open(false)}
    private fun open(resume:Boolean){
        val client=Rtmp(target)
        pending.set(client)
        try{
            check(!closed.get())
            client.connect()
            val session=Session(client,resume)
            active.set(session)
            if(closed.get()){active.compareAndSet(session,null);error("closed")}
        }catch(error:Exception){client.close();throw error}
        finally{pending.compareAndSet(client,null)}
    }
    private fun recover(session:Session){
        if(!active.compareAndSet(session,null))return
        session.client.close()
        if(closed.get())return
        status(false)
        worker=thread(name="RTMP reconnect",isDaemon=true){
            try{
                var delay=500L
                while(!closed.get()){
                    Thread.sleep(delay)
                    if(closed.get())break
                    try{open(true);break}catch(_:Exception){delay=minOf(10000L,delay*2)}
                }
            }catch(_:InterruptedException){}
            if(!closed.get() && active.get()!=null)runCatching{requestKey()}
        }
    }
    fun sentBoth()=active.get()?.announced==true
    fun media(type:Int,time:Int,packet:ByteArray){
        val config=packet.getOrNull(1)?.toInt()==0
        if(config){if(type==8)audioHeader=packet else if(type==9)videoHeader=packet}
        val session=active.get()?:return
        try{
            if(session.resumed && session.epoch==null){
                if(config || type!=9 || (packet[0].toInt() and 0xf0)!=0x10)return
                val a=audioHeader?:return
                val v=videoHeader?:return
                session.client.media(9,0,v);session.client.media(8,0,a)
                session.epoch=time
            }
            session.client.media(type,(time-(session.epoch?:0)).coerceAtLeast(0),packet)
            if(!config && type==8)session.audio=true
            if(!config && type==9)session.video=true
            if(!session.announced && session.audio && session.video){
                session.announced=true
                if(session.resumed)status(true)
            }
        }catch(_:Exception){recover(session)}
    }
    override fun close(){
        closed.set(true)
        worker?.interrupt()
        pending.getAndSet(null)?.close()
        active.getAndSet(null)?.client?.close()
    }
}
