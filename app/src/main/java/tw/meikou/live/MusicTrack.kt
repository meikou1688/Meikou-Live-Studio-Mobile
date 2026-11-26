package tw.meikou.live

import android.content.Context
import android.media.*
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/** Decode a scene playlist off the microphone thread. Only captured mic blocks drive the mix. */
internal class MusicTrack(private val context:Context,private val report:(String)->Unit){
    private class Playback(val layer:Overlay){
        val samples=ArrayBlockingQueue<ShortArray>(12)
        @Volatile var stopped=false
        var worker:Thread?=null
        var current=ShortArray(0)
        var position=0
        fun stop(){stopped=true;worker?.interrupt()}
    }
    private var playback:Playback?=null
    fun close(){playback?.stop();playback=null}
    fun mix(layer:Overlay?,buffer:ByteBuffer,count:Int){
        if(layer?.id!=playback?.layer?.id){
            close()
            if(layer!=null && layer.items.isNotEmpty()){
                val session=Playback(layer)
                playback=session
                session.worker=thread(name="Scene music",isDaemon=true){play(session)}
            }
        }
        val session=playback?:return
        buffer.order(ByteOrder.nativeOrder())
        val volume=layer?.volume?:0
        for(i in 0 until count step 2){
            if(session.position>=session.current.size){
                session.current=session.samples.poll()?:return
                session.position=0
            }
            val sample=buffer.getShort(i)+session.current[session.position++]*volume/100
            buffer.putShort(i,sample.coerceIn(-32768,32767).toShort())
        }
    }
    private fun play(session:Playback){
        var index=0
        var failures=0
        val items=session.layer.items
        while(!session.stopped){
            try{decode(Uri.parse(items[index]),session);failures=0}
            catch(e:InterruptedException){return}
            catch(e:Exception){
                if(session.stopped)return
                report("無法播放音樂清單第 "+(index+1)+" 首")
                if(++failures>=items.size)return
            }
            index=(index+1)%items.size
        }
    }
    private fun decode(uri:Uri,session:Playback){
        val extractor=MediaExtractor()
        var decoder:MediaCodec?=null
        try{
            extractor.setDataSource(context,uri,null)
            val audio=(0 until extractor.trackCount).first{extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true}
            extractor.selectTrack(audio)
            var format=extractor.getTrackFormat(audio)
            val mime=requireNotNull(format.getString(MediaFormat.KEY_MIME))
            var converter=Converter(format,session.samples::put)
            var produced=false
            if(mime=="audio/raw"){
                val data=ByteBuffer.allocateDirect(131072)
                while(!session.stopped){
                    data.clear()
                    val n=extractor.readSampleData(data,0)
                    if(n<0)break
                    data.position(0);data.limit(n);converter.convert(data);produced=produced || n>0
                    extractor.advance()
                }
            }else{
                val codec=MediaCodec.createDecoderByType(mime)
                decoder=codec;codec.configure(format,null,null,0);codec.start()
                var eos=false
                val info=MediaCodec.BufferInfo()
                while(!session.stopped){
                    if(!eos){
                        val input=codec.dequeueInputBuffer(5000)
                        if(input>=0){
                            val data=requireNotNull(codec.getInputBuffer(input));data.clear()
                            val n=extractor.readSampleData(data,0)
                            if(n<0){codec.queueInputBuffer(input,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);eos=true}
                            else{codec.queueInputBuffer(input,0,n,extractor.sampleTime,0);extractor.advance()}
                        }
                    }
                    val index=codec.dequeueOutputBuffer(info,5000)
                    if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){format=codec.outputFormat;converter=Converter(format,session.samples::put)}
                    if(index>=0){
                        try{
                            if(info.size>0){
                                val pcm=requireNotNull(codec.getOutputBuffer(index)).duplicate()
                                pcm.position(info.offset);pcm.limit(info.offset+info.size)
                                converter.convert(pcm);produced=true
                            }
                        }finally{codec.releaseOutputBuffer(index,false)}
                        if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0)break
                    }
                }
            }
            if(!session.stopped){check(produced);converter.flush()}
        }finally{runCatching{decoder?.stop()};decoder?.release();extractor.release()}
    }
    private class Converter(format:MediaFormat,private val output:(ShortArray)->Unit){
        private val rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        private val channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        private val encoding=if(format.containsKey(MediaFormat.KEY_PCM_ENCODING))format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
        private var block=ShortArray(1024)
        private var used=0
        private val resampler=PcmResampler(rate)
        init{require(rate>0 && channels>0 && encoding in listOf(AudioFormat.ENCODING_PCM_FLOAT,AudioFormat.ENCODING_PCM_16BIT))}
        private fun emit(sample:Int){
            block[used++]=sample.toShort()
            if(used==block.size){output(block);block=ShortArray(1024);used=0}
        }
        fun flush(){if(used>0){output(block.copyOf(used));used=0}}
        fun convert(bytes:ByteBuffer){
            bytes.order(ByteOrder.nativeOrder())
            val stride=channels*(if(encoding==AudioFormat.ENCODING_PCM_FLOAT)4 else 2)
            while(bytes.remaining()>=stride){
                var sum=0L
                repeat(channels){sum+=if(encoding==AudioFormat.ENCODING_PCM_FLOAT)(bytes.float.coerceIn(-1f,1f)*32767).roundToInt() else bytes.short.toInt()}
                val sample=(sum/channels).toInt()
                resampler.accept(sample,::emit)
            }
        }
    }
}
