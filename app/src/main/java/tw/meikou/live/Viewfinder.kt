package tw.meikou.live

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.view.Surface

/** Main-thread Camera2 preview. Completes handoff only after CameraDevice.onClosed. */
class Viewfinder(private val ctx:Context,private val lens:Lens,private val surface:Surface,
                 private val failure:()->Unit, private val closed:()->Unit){
    private var device:CameraDevice?=null
    private var session:CameraCaptureSession?=null
    private var stopRequested=false
    private var zoom=1f
    private var request:CaptureRequest.Builder?=null
    private var ended=false
    private fun done(){if(!ended){ended=true;closed()}}
    fun stop(){stopRequested=true;session?.close();device?.close()}
    fun zoom(value:Float){zoom=value;runCatching{repeatRequest()}.onFailure{failure();stop()}}
    private fun repeatRequest(){
        val builder=request?:return
        if(lens.physical==null && lens.zoomMax>lens.zoomMin)builder.set(CaptureRequest.CONTROL_ZOOM_RATIO,zoom.coerceIn(lens.zoomMin,lens.zoomMax))
        session?.setRepeatingRequest(builder.build(),null,null)
    }
    @SuppressLint("MissingPermission")
    fun start(){
        val manager=ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try{manager.openCamera(lens.parent,ctx.mainExecutor,object:CameraDevice.StateCallback(){
            override fun onOpened(c:CameraDevice){
                device=c;if(stopRequested){c.close();return}
                try{
                    val output=OutputConfiguration(surface).apply{lens.physical?.let(::setPhysicalCameraId)}
                    request=c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply{addTarget(surface)}
                    c.createCaptureSession(SessionConfiguration(0,listOf(output),ctx.mainExecutor,object:CameraCaptureSession.StateCallback(){
                        override fun onConfigured(s:CameraCaptureSession){if(stopRequested){s.close();return};session=s;try{repeatRequest()}catch(e:Exception){failure();stop()}}
                        override fun onConfigureFailed(s:CameraCaptureSession){s.close();failure();stop()}
                    }))
                }catch(e:Exception){failure();stop()}
            }
            override fun onClosed(c:CameraDevice){done()}
            override fun onDisconnected(c:CameraDevice){failure();c.close()}
            override fun onError(c:CameraDevice,error:Int){failure();c.close()}
        })}catch(e:Exception){failure();done()}
    }
}

