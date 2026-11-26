package tw.meikou.live

import android.hardware.camera2.*
import android.media.MediaCodec
import android.util.Size
import java.util.Locale

data class Lens(val parent:String,val physical:String?,val focal:FloatArray,val sensorWidth:Float?,
                val sizes:List<Size>,val rates:List<Int>,val zoomMin:Float,val zoomMax:Float,val front:Boolean) {
    val equivalent:Float? get()=sensorWidth?.takeIf{it>0}?.let{width->focal.firstOrNull()?.times(36f/width)}
    override fun toString():String {
        val prefix=if(front)"前鏡頭" else "後鏡頭"
        val group=if(physical!=null)"實體 "+physical else "相機 "+parent
        val mm=equivalent?.let{" · 約 %.0f mm".format(Locale.TAIWAN,it)} ?: ""
        return prefix+" "+group+mm
    }
}
fun inventory(m:CameraManager):List<Lens> = buildList{
    for(id in m.cameraIdList){
        val parent=m.getCameraCharacteristics(id)
        for(physical in listOf<String?>(null)+parent.physicalCameraIds.sorted()){
            val c=runCatching{m.getCameraCharacteristics(physical?:id)}.getOrNull()?:continue
            val map=c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]?:continue
            val sizes=map.getOutputSizes(MediaCodec::class.java).orEmpty().filter{it.width>=640 && it.width<=3840 && it.width*9==it.height*16}.distinct().sortedBy{it.width}
            if(sizes.isEmpty())continue
            val zoom=parent[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE]
            val rates=c[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES].orEmpty().map{it.upper}.distinct().sorted()
            add(Lens(id,physical,c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?:floatArrayOf(),c[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]?.width,sizes,rates,if(physical==null)zoom?.lower?:1f else 1f,if(physical==null)zoom?.upper?:1f else 1f,c[CameraCharacteristics.LENS_FACING]==CameraCharacteristics.LENS_FACING_FRONT))
        }
    }
}.sortedBy{it.front}

