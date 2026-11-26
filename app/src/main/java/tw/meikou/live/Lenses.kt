package tw.meikou.live

import android.hardware.camera2.*
import android.util.Size
import java.util.Locale

data class Lens(val parent:String,val physical:String?,val focal:FloatArray,val sensorWidth:Float?,
                val sizes:List<Size>,val rates:List<Int>,val zoomMin:Float,val zoomMax:Float,val front:Boolean,val sensorOrientation:Int,
                val sources:Map<Size,Size>, val supportedRates:Map<Size,List<Int>>) {
    fun sourceFor(width:Int,height:Int):Size = sources.getValue(Size(maxOf(width,height),minOf(width,height)))
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
        val parent=runCatching{m.getCameraCharacteristics(id)}.getOrNull()?:continue
        if(parent[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)!=true)continue
        for(physical in listOf<String?>(null)+parent.physicalCameraIds.sorted()){
            val c=runCatching{m.getCameraCharacteristics(physical?:id)}.getOrNull()?:continue
            val map=c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]?:continue
            val outputs=map.getOutputSizes(android.graphics.SurfaceTexture::class.java).orEmpty()
                .filter { it.width in 320..3840 && it.height in 1..it.width }
            if(outputs.isEmpty())continue
            val wide=outputs.filter { it.width*9==it.height*16 }.distinct().sortedBy { it.width }
            val candidates=wide.ifEmpty { listOf(Size(640,360),Size(1280,720),Size(1920,1080))
                .filter { size -> size.width <= maxOf(640,outputs.maxOf { it.width }) } }
            val sources=candidates.associateWith { size -> outputs.minWith(compareBy<Size>(
                { if(it==size) 0 else 1 }, { kotlin.math.abs(it.width-size.width) },
                { kotlin.math.abs(it.height-size.height) })) }
            val zoom=parent[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE]
            val parentRates=parent[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES].orEmpty().map { it.upper }
            val rates=c[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES].orEmpty()
                .map { it.upper }.filter { it in parentRates }.distinct().sorted()
            val supported=sources.mapValues { (_,source) ->
                val duration=runCatching { map.getOutputMinFrameDuration(android.graphics.SurfaceTexture::class.java,source) }.getOrDefault(0L)
                rates.filter { fps -> duration<=0 || fps<=Math.round(1e9/duration) }
            }
            val sizes=candidates.filter { supported.getValue(it).isNotEmpty() }
            if(sizes.isEmpty())continue
            add(Lens(id,physical,c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?:floatArrayOf(),c[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]?.width,sizes,rates,if(physical==null)zoom?.lower?:1f else 1f,if(physical==null)zoom?.upper?:1f else 1f,c[CameraCharacteristics.LENS_FACING]==CameraCharacteristics.LENS_FACING_FRONT,c[CameraCharacteristics.SENSOR_ORIENTATION]?:90,sources,supported))
        }
    }
}.sortedWith(compareBy<Lens>{it.front}.thenBy{it.physical!=null}).distinctBy{it.physical?:it.parent}


internal fun Lens.rotation(ctx:android.content.Context):Int {
    val rotation=(ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation*90
    return (sensorOrientation + (if(front) rotation else -rotation) + 360)%360
}
