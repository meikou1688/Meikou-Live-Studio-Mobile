package tw.meikou.live

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest

data class CameraSettings(val focus:Float?=null,val whiteBalance:Int?=null,val iso:Int?=null,val exposureNs:Long?=null){
    fun applyTo(builder:CaptureRequest.Builder,baseline:CaptureRequest){
        fun <T> restore(key:CaptureRequest.Key<T>){baseline[key]?.let{builder.set(key,it)}}
        if(focus!=null){
            builder.set(CaptureRequest.CONTROL_AF_MODE,CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE,focus)
        }else restore(CaptureRequest.CONTROL_AF_MODE)
        if(whiteBalance!=null)builder.set(CaptureRequest.CONTROL_AWB_MODE,whiteBalance) else restore(CaptureRequest.CONTROL_AWB_MODE)
        if(iso!=null && exposureNs!=null){
            builder.set(CaptureRequest.CONTROL_AE_MODE,CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY,iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME,exposureNs)
        }else restore(CaptureRequest.CONTROL_AE_MODE)
    }
}
