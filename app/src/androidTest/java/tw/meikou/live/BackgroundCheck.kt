package tw.meikou.live

import android.app.*
import android.content.Intent
import android.os.*
import android.widget.*
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask

/** Exercises the production Activity, Camera2, AudioRecord, encoder, notification and RTMP publisher. */
class BackgroundCheck : Instrumentation() {
    private var srt=false
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);srt=arguments?.getString("protocol")=="srt";start()}
    private fun <T> ui(block:()->T):T{
        val task=FutureTask(Callable(block))
        runOnMainSync(task)
        return task.get()
    }
    private fun field(activity:Activity,name:String):Any? =
        activity.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(activity)
    private fun until(check:()->Boolean){
        val deadline=SystemClock.elapsedRealtime()+30000
        while(!check()){
            require(SystemClock.elapsedRealtime()<deadline){"等待狀態逾時"}
            SystemClock.sleep(150)
        }
    }
    private fun checkLandscapeEditing(activity:Activity){
        val scenes=ui { (activity.javaClass.getDeclaredMethod("getScenes").apply{isAccessible=true}.invoke(activity) as Scenes) }
        val editor=ui { field(activity,"sceneEditor") as SceneEditor }
        val canvas=ui { editor.javaClass.getDeclaredField("preview").apply{isAccessible=true}.get(editor) as LayerCanvas }
        ui {scenes.layers(listOf(Overlay(id="qr-test",kind="qr",value="Meikou-camera-check",x=.3f,y=.5f,width=.20f)))}
        until { scenes.previewLayers.any{it.id=="qr-test"} }
        fun drag(x:Float,y:Float,toX:Float,toY:Float){
            ui{
                val start=SystemClock.uptimeMillis()
                for((index,action) in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_MOVE,android.view.MotionEvent.ACTION_UP).withIndex()){
                    val event=android.view.MotionEvent.obtain(start,start+index*16L,action,if(index==0)x else toX,if(index==0)y else toY,0)
                    try{canvas.dispatchTouchEvent(event)}finally{event.recycle()}
                }
            }
        }
        val w=canvas.width.toFloat();val h=canvas.height.toFloat()
        check(w>h)
        drag(.30f*w,.50f*h,.62f*w,.55f*h)
        until { scenes.previewLayers.singleOrNull()?.let{kotlin.math.abs(it.x-.62f)<.01f}==true }
        val frame=scenes.previewLayers.single()
        val dx=frame.width*w/2;val dy=frame.height*h/2
        drag(frame.x*w+dx,frame.y*h+dy,frame.x*w+dx*1.5f,frame.y*h+dy*1.5f)
        until { scenes.previewLayers.singleOrNull()?.let{kotlin.math.abs(it.width-.30f)<.015f}==true }
        val slides=listOf(android.graphics.Color.RED,android.graphics.Color.BLUE).mapIndexed{index,color->
            val file=java.io.File(targetContext.cacheDir,"slide-"+index+".png")
            val bitmap=android.graphics.Bitmap.createBitmap(64,64,android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            file.outputStream().use{check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
            bitmap.recycle()
            android.net.Uri.fromFile(file).toString()
        }
        val wav=java.io.File(targetContext.cacheDir,"music-test.wav")
        val rate=44100
        val pcm=java.nio.ByteBuffer.allocate(44+rate*4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        pcm.put("RIFF".toByteArray()).putInt(36+rate*4).put("WAVEfmt ".toByteArray()).putInt(16)
        pcm.putShort(1).putShort(2).putInt(rate).putInt(rate*4).putShort(4).putShort(16)
        pcm.put("data".toByteArray()).putInt(rate*4)
        for(i in 0 until rate){
            val tone=(12000*kotlin.math.sin(2*Math.PI*997*i/rate)).toInt().toShort()
            pcm.putShort(tone).putShort(tone)
        }
        wav.writeBytes(pcm.array())
        ui { scenes.cameraEffect(1);scenes.layers(scenes.active.layers+listOf(
            Overlay(kind="slides",value="",x=.18f,y=.5f,width=.18f,items=slides,slideSeconds=2),
            Overlay(kind="music",value="",items=listOf(android.net.Uri.fromFile(wav).toString()),volume=50))) }
    }
    override fun onStart(){
        val result=Bundle()
        try{
            val launch=Intent(targetContext,Studio::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val activity=startActivitySync(launch)
            ui { (field(activity,"address") as EditText).setText(if(srt)"srt://10.0.2.2:19001/integration" else "rtmp://127.0.0.1:1935/live")
                (field(activity,"streamKey") as EditText).setText(if(srt)"" else "integration")
                (field(activity,"saveLive") as CheckBox).isChecked=true
                if(!srt)activity.javaClass.getDeclaredField("additional").apply{isAccessible=true}
                    .set(activity,(1936..1940).map { java.net.URI("rtmp://127.0.0.1:"+it+"/live/integration") })
                (field(activity,"backgroundLive") as CheckBox).isChecked=true }
            until { ui { (field(activity,"rates") as Spinner).count>0 && field(activity,"finder")!=null } }
            checkLandscapeEditing(activity)
            ui { (field(activity,"live") as Button).performClick() }
            until { ui { (field(activity,"status") as TextView).text.startsWith("直播中") } }
            SystemClock.sleep(2500)
            uiAutomation.performGlobalActionCompatHome()
            until { ui { field(activity,"visible")==false } }
            SystemClock.sleep(10000)
            targetContext.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            until { ui { field(activity,"visible")==true && (field(activity,"ready")==true) } }
            SystemClock.sleep(2500)
            val manager=targetContext.getSystemService(NotificationManager::class.java)
            manager.activeNotifications.first { it.id==12 }.notification.actions.first().actionIntent.send()
            until { ui { field(activity,"capture")==null } && manager.activeNotifications.none { it.id==12 } }
            result.putString("stream","MEIKOU_BACKGROUND_OK")
            finish(Activity.RESULT_OK,result)
        }catch(error:Throwable){
            result.putString("stream","FAILED: "+error.stackTraceToString())
            finish(Activity.RESULT_CANCELED,result)
        }
    }
    private fun UiAutomation.performGlobalActionCompatHome(){
        executeShellCommand("input keyevent KEYCODE_HOME").use {
            ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
    }
}
