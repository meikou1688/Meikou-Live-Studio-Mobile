package tw.meikou.live

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.media.*
import android.os.*
import android.view.Surface
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class Profile(val width:Int,val height:Int,val fps:Int,val bitrate:Int,val hevc:Boolean)

class Capture(private val ctx:Context, private val preview:Surface?, private val profile:Profile,private val scenes:Scenes,
              private var lens:Lens?, private val inputDevice:AudioDeviceInfo?, private val initialZoom:Float, private var exposureIndex:Int, private var settings:CameraSettings, private val telemetry:(String)->Unit, private val target:URI?, additional:List<URI>, outputStatus:(Int,String)->Unit, private val file:File?, private val notify:(Int)->Unit,
              private val finished:(Boolean,Boolean,Boolean)->Unit,
              private val projection:android.media.projection.MediaProjection?=null,private val unsupportedLens:((Lens)->Unit)?=null) {
    private var virtualDisplay:android.hardware.display.VirtualDisplay?=null
    private val projectionCallback=object:android.media.projection.MediaProjection.Callback(){override fun onStop(){stop()}}
    private val stopping=AtomicBoolean()
    private val bad=AtomicBoolean()
    private val additionalOutputs=additional.isNotEmpty()
    private val extra=ExtraOutputs(additional,outputStatus)
    private val client=target?.takeUnless { it.scheme=="srt" }?.let { destination ->
        ReconnectingRtmp(destination,{up->if(!stopping.get())notify(if(up)1 else 3)},
            {video?.setParameters(Bundle().apply{putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0)})})
    }
    private val srt=target?.takeIf { it.scheme=="srt" }?.let { SrtOutput(it,profile.hevc) }
    private val camThread=HandlerThread("Camera")
    private var camera:CameraDevice?=null
    private var session:CameraCaptureSession?=null
    private var video:MediaCodec?=null
    private var audio:MediaCodec?=null
    private var mic:AudioRecord?=null
    var mixPlayback=false
    private var playback:AudioRecord?=null
    private val music=MusicTrack(ctx,telemetry)
    private val playbackBuffer=ByteBuffer.allocateDirect(2048)
    var reduceNoise=false
    private var suppressor:android.media.audiofx.NoiseSuppressor?=null
    private var encoderSurface:Surface?=null
    private var compositor:SceneCompositor?=null
    private var monitor=preview
    @Synchronized fun monitorSurface(surface:Surface?){monitor=surface;compositor?.monitorSurface(surface)}
    private var mux:MediaMuxer?=null
    val microphoneMuted=AtomicBoolean()
    private val recorded=BooleanArray(2)
    private var recordingFailed=false
    private var muxStarted=false
    private val tracks=IntArray(2){-1}
    private val pending=ArrayList<Frame>()
    private val timeline=CaptureTimeline { video?.setParameters(Bundle().apply{putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0)}) }
    private var published=false
    private var onlineFailure=false
    private var currentBitrate=profile.bitrate
    private var probeStart=0L
    private var writeTime=0L
    private var stableWindows=0
    private var startNs=0L
    private data class PcmBlock(val samples:ByteArray,val pts:Long)
    private val pcmQueue=ArrayBlockingQueue<PcmBlock>(64)
    private var microphoneThread:Thread?=null
    private var endAudioUs=0L
    private val delivered=BooleanArray(2)
    private val cameraReady=CountDownLatch(1)
    private val cameraLock=Any()

    fun stop(){stopping.set(true);client?.close();srt?.close();extra.close();cameraReady.countDown()}
    private fun fail(){bad.set(true);stop()}
    fun start()=thread(name="Capture") {
        try {
            camThread.start()
            if(stopping.get())return@thread
            try { client?.connect();srt?.connect() } catch(e:java.io.IOException){onlineFailure=true;throw e}
            if(!stopping.get()){
                extra.start()
                prepare()
                if(projection!=null){
                    projection.registerCallback(projectionCallback,Handler(camThread.looper))
                    virtualDisplay=requireNotNull(projection.createVirtualDisplay("Meikou Live",profile.width,profile.height,
                        ctx.resources.displayMetrics.densityDpi,android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        compositor!!.input,null,Handler(camThread.looper)))
                }else{
                    openCamera()
                    check(cameraReady.await(8,TimeUnit.SECONDS))
                }
                if(!stopping.get()){
                    startNs=System.nanoTime()
                    playback?.let{it.startRecording();check(it.recordingState==AudioRecord.RECORDSTATE_RECORDING)}
                    mic!!.startRecording()
                    check(mic!!.recordingState==AudioRecord.RECORDSTATE_RECORDING)
                    compositor!!.captureEpochNs=startNs
                    microphoneThread=thread(name="Microphone"){readMicrophone()}
                    while(!stopping.get()){feedMicrophone();drain(video!!,false);drain(audio!!,true)}
                }
            }
        }catch(e:Exception){if(!stopping.get())bad.set(true)}
        finally{
            stopping.set(true)
            synchronized(cameraLock){runCatching{session?.close()};runCatching{camera?.close()}}
            runCatching{virtualDisplay?.release()}
            runCatching{projection?.unregisterCallback(projectionCallback)}
            runCatching{projection?.stop()}
            runCatching{mic?.stop()}
            runCatching{microphoneThread?.join(2000)}
            music.close()
            runCatching{compositor?.close()}
            runCatching{playback?.stop()};runCatching{playback?.release()}
            runCatching{suppressor?.release()};runCatching{mic?.release()}
            if(muxStarted && !recordingFailed){
                runCatching{video!!.signalEndOfInputStream();val id=audio!!.dequeueInputBuffer(20000)
                    if(id>=0)audio!!.queueInputBuffer(id,0,0,endAudioUs,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    repeat(12){drain(video!!,false);drain(audio!!,true);Thread.sleep(5)}
                }.onFailure{bad.set(true)}
            }
            listOf(video,audio).forEach{runCatching{it?.stop()};runCatching{it?.release()}}
            runCatching{encoderSurface?.release()}
            if(muxStarted)runCatching{mux!!.stop()}.onFailure{recordingFailed=true}
            runCatching{mux?.release()};client?.close();srt?.close();extra.close();camThread.quitSafely()
            val ok=!bad.get() && delivered.all{it}
            val saved=muxStarted && !recordingFailed && recorded.all{it}
            if(!saved)file?.delete()
            finished(ok,onlineFailure,saved)
        }
    }
    @SuppressLint("MissingPermission")
    private fun prepare(){
        val mime=if(profile.hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val vf=MediaFormat.createVideoFormat(mime,profile.width,profile.height).apply{
            setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE,profile.bitrate);setInteger(MediaFormat.KEY_FRAME_RATE,profile.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,2)
            if(!profile.hevc)setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        }
        val chosen=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull{
            it.isEncoder && it.isHardwareAccelerated && it.supportedTypes.contains(mime) &&
                runCatching{it.getCapabilitiesForType(mime).isFormatSupported(vf)}.getOrDefault(false)
        }
        video=(chosen?.let{MediaCodec.createByCodecName(it.name)} ?: MediaCodec.createEncoderByType(mime)).also{video=it;it.configure(vf,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);encoderSurface=it.createInputSurface();it.start()}
        val renderer=SceneCompositor(ctx,profile.width,profile.height,null,lens,encoderSurface!!,scenes){fail()}
        synchronized(this){compositor=renderer;renderer.monitorSurface(monitor)}
        val af=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,48000,1).apply{
            setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);setInteger(MediaFormat.KEY_BIT_RATE,96000)}
        audio=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also{audio=it;it.configure(af,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);it.start()}
        val min=AudioRecord.getMinBufferSize(48000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        require(min>0)
        mic=AudioRecord(MediaRecorder.AudioSource.CAMCORDER,48000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(min*2,8192))
        check(mic!!.state==AudioRecord.STATE_INITIALIZED)
        if(mixPlayback){
            val policy=AudioPlaybackCaptureConfiguration.Builder(requireNotNull(projection))
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).excludeUid(android.os.Process.myUid()).build()
            val pcm=AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
            playback=AudioRecord.Builder().setAudioFormat(pcm).setBufferSizeInBytes(maxOf(min*2,8192))
                .setAudioPlaybackCaptureConfig(policy).build()
            check(playback!!.state==AudioRecord.STATE_INITIALIZED)
        }
        if(reduceNoise){
            suppressor=android.media.audiofx.NoiseSuppressor.create(mic!!.audioSessionId)
            check(suppressor?.setEnabled(true)==android.media.audiofx.AudioEffect.SUCCESS && suppressor?.enabled==true)
        }
        if(inputDevice!=null)check(mic!!.setPreferredDevice(inputDevice))
        file?.let{mux=MediaMuxer(it.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)}
    }
    @SuppressLint("MissingPermission")
    private fun openCamera(){
        val manager=ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val selected=requireNotNull(lens)
        val id=selected.parent
        val generation=++revision
        val handler=Handler(camThread.looper)
        manager.openCamera(id,object:CameraDevice.StateCallback(){
            override fun onOpened(c:CameraDevice){synchronized(cameraLock){
                if(stopping.get() || generation!=revision){c.close();return}
                camera=c
                try{
                    val surfaces=listOf(compositor!!.input)
                    val request=c.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply{
                        surfaces.forEach{addTarget(it)}
                        val ranges=manager.getCameraCharacteristics(id)[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES].orEmpty()
                        val range=ranges.filter{it.upper==profile.fps}.maxByOrNull{it.lower} ?: error("fps")
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,range)
                    }
                    val outputs=surfaces.map{android.hardware.camera2.params.OutputConfiguration(it).apply{
                        if(selected.physical!=null)setPhysicalCameraId(selected.physical)
                    }}
                    val callback=object:CameraCaptureSession.StateCallback(){
                        override fun onConfigured(s:CameraCaptureSession){synchronized(cameraLock){
                            if(stopping.get() || generation!=revision){s.close();return}
                            try{session=s;builder=request;baseline=request.build();applyRequest();cameraReady.countDown()}catch(e:Exception){fail()}
                        }}
                        override fun onConfigureFailed(s:CameraCaptureSession){s.close();if(generation==revision)fail()}
                    }
                    c.createCaptureSession(android.hardware.camera2.params.SessionConfiguration(0,outputs,java.util.concurrent.Executor{handler.post(it)},callback))
                }catch(e:Exception){fail()}
            }}
            override fun onClosed(c:CameraDevice){synchronized(cameraLock){
                if(c===closingForSwitch){closingForSwitch=null;if(!stopping.get())try{openCamera()}catch(e:Exception){fail()}}
            }}
            override fun onError(c:CameraDevice,error:Int){c.close();if(generation==revision)fail()}
            override fun onDisconnected(c:CameraDevice){c.close();if(generation==revision)fail()}
        },handler)
    }
    private var revision=0
    private var closingForSwitch:CameraDevice?=null
    private var baseline:CaptureRequest?=null
    private var builder:CaptureRequest.Builder?=null
    fun configure(value:CameraSettings){
        if(camThread.isAlive)Handler(camThread.looper).post{
            if(!stopping.get())try{settings=value;applyRequest()}catch(e:Exception){fail()}
        }
    }
    private var zoom=initialZoom
    private var lastTelemetry=""
    private fun applyRequest(){
        val req=builder?:return
        val lens=lens?:return
        baseline?.let{settings.applyTo(req,it)}
        req.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,exposureIndex)
        if(lens.physical==null && lens.zoomMax>lens.zoomMin)req.set(CaptureRequest.CONTROL_ZOOM_RATIO,zoom)
        var unmatched=0
        session?.setRepeatingRequest(req.build(),object:CameraCaptureSession.CaptureCallback(){
            override fun onCaptureCompleted(s:CameraCaptureSession,r:CaptureRequest,result:TotalCaptureResult){
                val active=result[CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID]
                if(s!==session)return
                val physical=result.physicalCameraResults[lens.physical]
                if(lens.physical!=null && physical==null && active!=lens.physical){
                    if(++unmatched==30)unsupportedLens?.invoke(lens)
                }else unmatched=0
                val focal=(physical?:result)[CaptureResult.LENS_FOCAL_LENGTH]
                val route=mic?.routedDevice?.productName ?: "未回報"
                val label="音訊 "+route+" · 作用中 ID "+(if(physical!=null)lens.physical else active?:if(lens.physical==null)lens.parent else "待確認")+" · "+(focal?.toString()?:"—")+" mm"
                if(label!=lastTelemetry){lastTelemetry=label;telemetry(label)}
            }
        },Handler(camThread.looper))
    }
    fun changeLens(next:Lens){
        if(!camThread.isAlive)return
        Handler(camThread.looper).post{
            if(stopping.get())return@post
            synchronized(cameraLock){
                  revision++;session?.close();session=null;builder=null
                lens=next;compositor?.changeLens(next);zoom=1f;exposureIndex=0;settings=CameraSettings()
                  val old=camera;camera=null
                  if(old!=null){closingForSwitch=old;old.close()}
                  else if(closingForSwitch==null)try{openCamera()}catch(e:Exception){fail()}
            }
        }
    }
    fun changeExposure(index:Int){
        if(camThread.isAlive)Handler(camThread.looper).post{
            if(!stopping.get())try{exposureIndex=index;applyRequest()}catch(e:Exception){fail()}
        }
    }
    fun changeZoom(value:Float){
        if(camThread.isAlive)Handler(camThread.looper).post{
            if(!stopping.get())try{val l=lens?:return@post;zoom=value.coerceIn(l.zoomMin,l.zoomMax);applyRequest()}catch(e:Exception){fail()}
        }
    }
    private fun readMicrophone(){
        val buffer=ByteBuffer.allocateDirect(2048)
        var nextUs=0L
        try{
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            while(!stopping.get()){
                buffer.clear()
                val n=mic!!.read(buffer,buffer.capacity(),AudioRecord.READ_BLOCKING)
                require(n>=0 && n%2==0)
                if(n==0)continue
                val duration=n.toLong()*1000000/(2*48000)
                val capturedUs=((System.nanoTime()-startNs)/1000-duration).coerceAtLeast(0)
                // Preserve source gaps if the device delivers fewer samples than real time.
                // Small scheduling fluctuations stay on the continuous PCM sample clock.
                if(nextUs-capturedUs>65000)continue
                if(nextUs==0L || capturedUs-nextUs>65000)nextUs=capturedUs
                if(microphoneMuted.get())silencePcm(buffer,n)
                playback?.let{
                    playbackBuffer.clear()
                    val read=it.read(playbackBuffer,n,AudioRecord.READ_NON_BLOCKING)
                    check(read>=0);mixPcm(buffer,playbackBuffer,read)
                }
                music.mix(scenes.active.layers.lastOrNull{it.kind=="music"},buffer,n)
                val bytes=ByteArray(n)
                buffer.position(0);buffer.get(bytes)
                val block=PcmBlock(bytes,nextUs)
                if(!pcmQueue.offer(block)){pcmQueue.poll();pcmQueue.offer(block)}
                nextUs+=duration
            }
        }catch(e:Exception){if(!stopping.get())fail()}
    }
    private fun feedMicrophone(){
        if(pcmQueue.isEmpty()){Thread.sleep(1);return}
        val codec=audio!!
        val index=codec.dequeueInputBuffer(1000)
        if(index<0)return
        val block=pcmQueue.poll()?:error("PCM queue")
        val buffer=codec.getInputBuffer(index)!!
        buffer.clear();buffer.put(block.samples)
        codec.queueInputBuffer(index,0,block.samples.size,block.pts,0)
        endAudioUs=block.pts+block.samples.size.toLong()*1000000/(2*48000)
    }
    private fun drain(c:MediaCodec,a:Boolean){
        val info=MediaCodec.BufferInfo()
        while(true){
            val i=c.dequeueOutputBuffer(info,0)
            if(i==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){format(a,c.outputFormat);continue}
            if(i<0)return
            try{
                if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0){
                    val b=c.getOutputBuffer(i)!!;b.position(info.offset);b.limit(info.offset+info.size)
                    val bytes=ByteArray(info.size);b.get(bytes)
                    val pts=info.presentationTimeUs.coerceAtLeast(0)
                    val frame=Frame(a,pts,info.flags,bytes)
                    timeline.accept(frame,::deliver)
                }
            }finally{c.releaseOutputBuffer(i,false)}
        }
    }
    private fun deliver(frame:Frame){
        val a=frame.audio
        val bytes=frame.bytes
        val pts=frame.pts
        if(mux!=null){
            if(muxStarted)writeRecording(frame)
            else{require(pending.size<160);pending.add(frame)}
        }
        if(target!=null && !stopping.get()){
            try{srt?.send(a,bytes,pts,frame.flags)}catch(e:java.io.IOException){onlineFailure=!stopping.get();throw e}
            if(client!=null || (!profile.hevc && additionalOutputs))writeNetwork(frame)
        }
        if(!stopping.get()){
            delivered[if(a)1 else 0]=true
            if(!published && client?.sentBoth()!=false && srt?.sentBoth()!=false && delivered.all{it} && (mux==null || recorded.all{it})){
                published=true
                notify(if(target!=null)1 else 2)
            }
        }
    }
    private fun format(a:Boolean,f:MediaFormat){
        if(mux!=null){
            tracks[if(a)1 else 0]=mux!!.addTrack(f)
            if(tracks.all{it>=0}){mux!!.start();muxStarted=true;pending.forEach(::writeRecording);pending.clear()}
        }
        if(!a && !stopping.get())srt?.configure(f)
        if((client!=null || additionalOutputs) && !stopping.get()){
            fun csd(n:Int):ByteArray{val b=f.getByteBuffer("csd-"+n)!!.duplicate();return ByteArray(b.remaining()).also{b.get(it)}}
            val type=if(a)8 else 9
            val config=if(a)byteArrayOf(0xaf.toByte(),0)+csd(0) else Avc.config(Avc.units(csd(0))+Avc.units(csd(1)))
            client?.media(type,0,config);extra.send(type,0,config)
        }
    }
    private fun writeRecording(f:Frame){
        val index=if(f.audio)1 else 0
        val info=MediaCodec.BufferInfo().apply{set(0,f.bytes.size,f.pts,f.flags)}
        try{
            mux!!.writeSampleData(tracks[index],ByteBuffer.wrap(f.bytes),info)
            recorded[index]=true
        }catch(e:Exception){recordingFailed=true;throw e}
    }
    private fun writeNetwork(f:Frame){
            val packet=if(f.audio)byteArrayOf(0xaf.toByte(),1)+f.bytes else Avc.frame(f.bytes,f.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME!=0)
            val now=System.nanoTime()
            try{client?.media(if(f.audio)8 else 9,(f.pts/1000).toInt(),packet)}
            catch(e:Exception){onlineFailure=!stopping.get();throw e}
            extra.send(if(f.audio)8 else 9,(f.pts/1000).toInt(),packet)
            writeTime+=System.nanoTime()-now
            if(probeStart==0L)probeStart=now
            val elapsed=now-probeStart
            if(elapsed>=2_000_000_000L){
                val busy=writeTime.toDouble()/elapsed
                if(busy<0.12)stableWindows++ else stableWindows=0
                val next=when{
                    busy>0.45 -> maxOf(profile.bitrate/4,currentBitrate*3/4)
                    stableWindows>=3 -> minOf(profile.bitrate,currentBitrate+250000)
                    else ->currentBitrate
                }
                if(next!=currentBitrate)runCatching{
                    video!!.setParameters(Bundle().apply{putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE,next)})
                    currentBitrate=next
                }
                writeTime=0;probeStart=now
            }
    }
}
