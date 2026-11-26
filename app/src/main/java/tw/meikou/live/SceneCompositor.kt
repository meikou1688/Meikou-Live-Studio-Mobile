package tw.meikou.live

import android.graphics.*
import android.opengl.*
import android.opengl.GLES20.*
import android.os.*
import android.view.Surface
import java.nio.*
import java.util.concurrent.FutureTask
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

/** Camera, preview and encoder share the same scene snapshot and frame timestamp. */
class SceneCompositor(private val ctx:android.content.Context,private val width:Int,private val height:Int,private val monitor:Surface?,initialLens:Lens?,
                      private val encoder:Surface?,private val scenes:Scenes,private val failed:()->Unit){
    private val worker=HandlerThread("Scene GL").apply{start()}
    private val handler=Handler(worker.looper)
    private var display=EGL14.EGL_NO_DISPLAY
    private var context=EGL14.EGL_NO_CONTEXT
    private var previewWindow=EGL14.EGL_NO_SURFACE
    private var lastRotation=initialLens?.rotation(ctx)?:0
    private var windowConfig:EGLConfig?=null
    private var encodeWindow=EGL14.EGL_NO_SURFACE
    private var cameraProgram=0
    private var layerProgram=0
    private var cameraTex=0
    private lateinit var source:SurfaceTexture
    lateinit var input:Surface
        private set
    @Volatile var lens=initialLens
        private set
    fun changeLens(next:Lens)=onWorker {
        val size=next.sourceFor(width,height)
        source.setDefaultBufferSize(size.width,size.height)
        lens=next
    }
    private val cameraTransform=FloatArray(16)
    private val viewTransform=FloatArray(16)
    private val matrix=FloatArray(16)
    private val rawCrop=FloatArray(16)
    private val windowSize=IntArray(2)
    private val canvasViewport=IntArray(4)
    private val cameraViewport=IntArray(4)
    private val identity=FloatArray(16).also{android.opengl.Matrix.setIdentityM(it,0)}
    private val square=floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f)
    private fun floats(v:FloatArray)=ByteBuffer.allocateDirect(v.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply{put(v);position(0)}
    private val cameraUv=floats(floatArrayOf(0f,0f,1f,0f,0f,1f,1f,1f))
    private val layerUv=floats(floatArrayOf(0f,1f,1f,1f,0f,0f,1f,0f))
    private val coords=floats(square)
    private data class Sprite(val tex:Int,val aspect:Float,val stamp:String)
    private class Clip(val tex:Int,val texture:SurfaceTexture,val surface:Surface,val player:android.media.MediaPlayer){
        var prepared=false
        var pending=false
        var hasFrame=false
        var failed=false
        val transform=FloatArray(16)
        fun close(){player.release();surface.release();texture.release();glDeleteTextures(1,intArrayOf(tex),0)}
    }
    private val clips=mutableMapOf<String,Clip>()
    private fun clip(layer:Overlay):Clip{
        return clips.getOrPut(layer.id){
            val tex=texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            val texture=SurfaceTexture(tex)
            val surface=Surface(texture)
            val player=android.media.MediaPlayer()
            val clip=Clip(tex,texture,surface,player)
            texture.setOnFrameAvailableListener({clip.pending=true},handler)
            try{
                player.setDataSource(ctx,android.net.Uri.parse(layer.value))
                player.setSurface(surface);player.setVolume(0f,0f);player.isLooping=true
                player.setOnPreparedListener{if(!closing && clips[layer.id]===clip){clip.prepared=true;it.start()}}
                player.setOnErrorListener{_,_,_->clip.failed=true;true}
                player.prepareAsync()
            }catch(e:Exception){clip.failed=true}
            clip
        }
    }
    private val sprites=mutableMapOf<String,Sprite>()
    private val date=SimpleDateFormat("yyyy/MM/dd",Locale.TAIWAN)
    private val clock=SimpleDateFormat("HH:mm:ss",Locale.TAIWAN)
    @Volatile private var closing=false
    private fun <T> onWorker(block:()->T):T{val task=FutureTask(block);check(handler.post(task));return task.get()}
    init{try{onWorker{initGl()}}catch(e:Exception){close();throw e}}
    private fun program(fragment:String):Int{
        fun shader(type:Int,code:String):Int{
            val id=glCreateShader(type);glShaderSource(id,code);glCompileShader(id)
            val status=IntArray(1);glGetShaderiv(id,GL_COMPILE_STATUS,status,0);check(status[0]!=0){glGetShaderInfoLog(id)}
            return id
        }
        val vs=shader(GL_VERTEX_SHADER,"attribute vec2 pos; attribute vec2 uv; uniform mat4 transform; varying vec2 tex; void main(){gl_Position=vec4(pos,0.,1.);tex=(transform*vec4(uv,0.,1.)).xy;}")
        val fs=shader(GL_FRAGMENT_SHADER,fragment)
        val id=glCreateProgram();glAttachShader(id,vs);glAttachShader(id,fs);glLinkProgram(id)
        val status=IntArray(1);glGetProgramiv(id,GL_LINK_STATUS,status,0);check(status[0]!=0){glGetProgramInfoLog(id)}
        glDeleteShader(vs);glDeleteShader(fs);return id
    }
    private fun texture(target:Int):Int{
        val ids=IntArray(1);glGenTextures(1,ids,0);glBindTexture(target,ids[0])
        glTexParameteri(target,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(target,GL_TEXTURE_MAG_FILTER,GL_LINEAR)
        glTexParameteri(target,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(target,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE)
        return ids[0]
    }
    private fun initGl(){
        display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v=IntArray(2);check(EGL14.eglInitialize(display,v,0,v,1))
        val attrs=mutableListOf(EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8)
        if(encoder!=null)attrs.addAll(listOf(0x3142,1))
        attrs.add(EGL14.EGL_NONE)
        val configs=arrayOfNulls<EGLConfig>(1)
        check(EGL14.eglChooseConfig(display,attrs.toIntArray(),0,configs,0,1,v,0) && v[0]>0)
        windowConfig=configs[0]
        context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
        fun window(surface:Surface)=EGL14.eglCreateWindowSurface(display,configs[0],surface,intArrayOf(EGL14.EGL_NONE),0).also{check(it!=EGL14.EGL_NO_SURFACE)}
        if(monitor!=null)previewWindow=window(monitor)
        if(encoder!=null)encodeWindow=window(encoder)
        val first=if(monitor!=null)previewWindow else encodeWindow
        check(EGL14.eglMakeCurrent(display,first,first,context))
        val sample=" varying vec2 tex; void main(){gl_FragColor=texture2D(image,tex);}"
        cameraProgram=program("#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES image;"+sample)
        layerProgram=program("precision mediump float; uniform sampler2D image;"+sample)
        cameraTex=texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        source=SurfaceTexture(cameraTex);val captureSize=lens?.sourceFor(width,height);source.setDefaultBufferSize(captureSize?.width?:width,captureSize?.height?:height);input=Surface(source)
        source.setOnFrameAvailableListener({if(!closing)try{render()}catch(e:Exception){if(!closing)failed()}},handler)
    }
    private fun sprite(v:Overlay):Sprite?{
        val text=when(v.kind){
            "clock"->clock.format(Date())
            "date"->date.format(Date())
            "score"->runCatching { val s=org.json.JSONArray(v.value);"${s.getString(0)}  ${s.getInt(2)}：${s.getInt(3)}  ${s.getString(1)}" }.getOrElse{return null}
            else->v.value
        }
        val old=sprites[v.id]
        if(old!=null && old.stamp==text)return old
        val image=if(v.kind=="image")v.bitmap?:return null else if(v.kind=="qr"){
            val size=384
            val grid=com.google.zxing.qrcode.QRCodeWriter().encode(text,com.google.zxing.BarcodeFormat.QR_CODE,size,size,
                mapOf(com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8"))
            val pixels=IntArray(size*size){if(grid[it%size,it/size])Color.BLACK else Color.WHITE}
            Bitmap.createBitmap(pixels,size,size,Bitmap.Config.ARGB_8888)
        }else{
            val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;textSize=54f;typeface=Typeface.DEFAULT_BOLD}
            val w=ceil(pen.measureText(text)).toInt().coerceIn(1,2000)+24
            Bitmap.createBitmap(w,84,Bitmap.Config.ARGB_8888).also{
                val canvas=Canvas(it);canvas.drawColor(Color.argb(170,10,15,20));canvas.drawText(text,12f,59f,pen)
            }
        }
        val tex=old?.tex?:texture(GL_TEXTURE_2D);glBindTexture(GL_TEXTURE_2D,tex);GLUtils.texImage2D(GL_TEXTURE_2D,0,image,0)
        val result=Sprite(tex,image.width.toFloat()/image.height,text)
        if(v.kind!="image")image.recycle()
        sprites[v.id]=result;return result
    }
    private fun draw(program:Int,target:Int,tex:Int,uv:FloatBuffer,transform:FloatArray){
        glUseProgram(program)
        val p=glGetAttribLocation(program,"pos");val u=glGetAttribLocation(program,"uv")
        glEnableVertexAttribArray(p);glEnableVertexAttribArray(u)
        glVertexAttribPointer(p,2,GL_FLOAT,false,0,coords);glVertexAttribPointer(u,2,GL_FLOAT,false,0,uv)
        glUniformMatrix4fv(glGetUniformLocation(program,"transform"),1,false,transform,0)
        glActiveTexture(GL_TEXTURE0);glBindTexture(target,tex);glUniform1i(glGetUniformLocation(program,"image"),0)
        glDrawArrays(GL_TRIANGLE_STRIP,0,4);glDisableVertexAttribArray(p);glDisableVertexAttribArray(u)
    }
    private fun render(){
        source.updateTexImage();source.getTransformMatrix(matrix)
        val selected=lens
        if(previewWindow!=EGL14.EGL_NO_SURFACE)lastRotation=selected?.rotation(ctx)?:0
        val rotation=lastRotation
        android.opengl.Matrix.setIdentityM(viewTransform,0)
        android.opengl.Matrix.translateM(viewTransform,0,.5f,.5f,0f)
        android.opengl.Matrix.rotateM(viewTransform,0,rotation.toFloat(),0f,0f,1f)
        if(selected?.front==true && scenes.mirrorSelfie)android.opengl.Matrix.scaleM(viewTransform,0,-1f,1f,1f)
        android.opengl.Matrix.translateM(viewTransform,0,-.5f,-.5f,0f)
        if(selected!=null)CameraGeometry.cameraCrop(matrix,rawCrop)
        android.opengl.Matrix.multiplyMM(cameraTransform,0,if(selected!=null)rawCrop else matrix,0,viewTransform,0)
        val layers=scenes.active.layers
        val iter=sprites.iterator();while(iter.hasNext()){val e=iter.next();if(layers.none{it.id==e.key}){glDeleteTextures(1,intArrayOf(e.value.tex),0);iter.remove()}}
        val time=SystemClock.elapsedRealtime()
        val obsolete=clips.keys.filter{id->layers.none{it.id==id}}
        obsolete.forEach{clips.remove(it)?.close()}
        val visible=layers.mapNotNull{layer->
            if(layer.kind!="video")sprite(layer)?.let{layer to it}
            else{
                val clip=clip(layer)
                if(clip.pending && !clip.failed){
                    clip.pending=false;clip.texture.updateTexImage();clip.texture.getTransformMatrix(clip.transform);clip.hasFrame=true
                }
                if(clip.hasFrame && !clip.failed)layer to Sprite(clip.tex,clip.player.videoWidth.toFloat()/maxOf(1,clip.player.videoHeight),"")
                else null
            }
        }
        fun output(window:EGLSurface,record:Boolean){
            check(EGL14.eglMakeCurrent(display,window,window,context))
            check(EGL14.eglQuerySurface(display,window,EGL14.EGL_WIDTH,windowSize,0))
            check(EGL14.eglQuerySurface(display,window,EGL14.EGL_HEIGHT,windowSize,1))
            if(windowSize[0]<=0 || windowSize[1]<=0)return
            CameraGeometry.fit(width,height,windowSize[0],windowSize[1],canvasViewport)
            val landscapeInput=selected!=null && rotation%180==0
            val captureSize=selected?.sourceFor(width,height)
            val inputW=if(captureSize==null)width else if(landscapeInput)captureSize.width else captureSize.height
            val inputH=if(captureSize==null)height else if(landscapeInput)captureSize.height else captureSize.width
            CameraGeometry.fit(inputW,inputH,canvasViewport[2],canvasViewport[3],cameraViewport)
            glClearColor(0f,0f,0f,1f);glClear(GL_COLOR_BUFFER_BIT)
            glViewport(canvasViewport[0]+cameraViewport[0],canvasViewport[1]+cameraViewport[1],cameraViewport[2],cameraViewport[3])
            glDisable(GL_BLEND);coords.position(0);coords.put(square);coords.position(0)
            draw(cameraProgram,GLES11Ext.GL_TEXTURE_EXTERNAL_OES,cameraTex,cameraUv,cameraTransform)
            glViewport(canvasViewport[0],canvasViewport[1],canvasViewport[2],canvasViewport[3])
            glEnable(GL_BLEND);glBlendFunc(GL_ONE,GL_ONE_MINUS_SRC_ALPHA)
            val frames=if(record)null else mutableListOf<LayerFrame>()
            for((layer,img) in visible){
                val w=layer.width*width;val h=w/img.aspect
                val x=if(layer.kind=="ticker")((layer.x*width-time%600000*width/8000f)%(width+w)+(width+w))%(width+w)-w/2 else layer.x*width
                val y=layer.y*height
                frames?.add(LayerFrame(layer.id,x/width,y/height,w/width,h/height,layer.angle))
                val angle=Math.toRadians(layer.angle.toDouble());val c=cos(angle).toFloat();val s=sin(angle).toFloat()
                coords.position(0)
                for(i in 0..3){val dx=square[2*i]*w/2;val dy=-square[2*i+1]*h/2
                    coords.put(2*(x+c*dx-s*dy)/width-1);coords.put(1-2*(y+s*dx+c*dy)/height)}
                coords.position(0)
                if(layer.kind=="video")draw(cameraProgram,GLES11Ext.GL_TEXTURE_EXTERNAL_OES,img.tex,cameraUv,clips.getValue(layer.id).transform)
                else draw(layerProgram,GL_TEXTURE_2D,img.tex,layerUv,identity)
            }
            if(record)EGLExt.eglPresentationTimeANDROID(display,window,source.timestamp)
            check(EGL14.eglSwapBuffers(display,window))
            if(frames!=null)scenes.previewLayers=frames
        }
        if(encodeWindow!=EGL14.EGL_NO_SURFACE)output(encodeWindow,true)
        if(previewWindow!=EGL14.EGL_NO_SURFACE){
            if(encoder==null)output(previewWindow,false)
            else try{output(previewWindow,false)}catch(e:IllegalStateException){
                EGL14.eglMakeCurrent(display,encodeWindow,encodeWindow,context)
                EGL14.eglDestroySurface(display,previewWindow)
                previewWindow=EGL14.EGL_NO_SURFACE
            }
        }
    }
    fun monitorSurface(surface:Surface?){
        if(closing || encoder==null)return
        handler.post {
            if(closing)return@post
            EGL14.eglMakeCurrent(display,encodeWindow,encodeWindow,context)
            if(previewWindow!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,previewWindow)
            previewWindow=if(surface?.isValid==true)EGL14.eglCreateWindowSurface(display,windowConfig,surface,intArrayOf(EGL14.EGL_NONE),0) else EGL14.EGL_NO_SURFACE
        }
    }
    fun close(){
        if(closing)return
        closing=true
        try{onWorker{
            clips.values.forEach{it.close()};clips.clear()
            if(::source.isInitialized){source.setOnFrameAvailableListener(null);source.release()}
            if(::input.isInitialized)input.release()
            if(display!=EGL14.EGL_NO_DISPLAY){
                EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
                for(w in listOf(previewWindow,encodeWindow))if(w!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,w)
                if(context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,context)
                EGL14.eglTerminate(display);EGL14.eglReleaseThread()
            }
        }}finally{worker.quitSafely()}
    }
}
