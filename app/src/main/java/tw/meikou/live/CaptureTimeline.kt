package tw.meikou.live

import java.util.ArrayDeque

internal data class Frame(val audio:Boolean,val pts:Long,val flags:Int,val bytes:ByteArray)

/** Emit decodable video and audio on one clock. Both tracks end at their shared capture watermark. */
internal class CaptureTimeline(private val requestKey:()->Unit){
    private val video=ArrayDeque<Frame>()
    private val audio=ArrayDeque<Frame>()
    private var origin:Long?=null
    private var videoTime=-1L
    private var audioTime=-1L

    fun accept(frame:Frame,emit:(Frame)->Unit){
        if(frame.audio){audio.addLast(frame);audioTime=frame.pts}
        else{video.addLast(frame);videoTime=frame.pts}
        check(video.size<=240 && audio.size<=240){"影音擷取其中一軌逾時"}
        if(origin==null){
            while(video.isNotEmpty() && video.first.flags and 1==0)video.removeFirst()
            val key=video.peekFirst()?:return
            while(audio.isNotEmpty() && audio.first.pts<key.pts)audio.removeFirst()
            val firstAudio=audio.peekFirst()?:return
            if(firstAudio.pts-key.pts>250000){
                video.removeFirst()
                requestKey()
                return
            }
            origin=key.pts
        }
        val watermark=minOf(videoTime,audioTime)
        while(true){
            val v=video.peekFirst()
            val a=audio.peekFirst()
            val next=when{
                v!=null && v.pts<=watermark && (a==null || v.pts<=a.pts)->video.removeFirst()
                a!=null && a.pts<=watermark->audio.removeFirst()
                else->return
            }
            emit(next.copy(pts=next.pts-requireNotNull(origin)))
        }
    }
}
