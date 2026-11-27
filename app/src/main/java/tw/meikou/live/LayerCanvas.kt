package tw.meikou.live

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Paint
import android.view.View
import kotlin.math.*

/** Touch targets share the preview's measured rectangle and rendered draw order. */
class LayerCanvas(ctx:Context, private val scenes:Scenes):View(ctx) {
    var selection:()->String? = {null}
    private val density=resources.displayMetrics.density
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0xff00e5c0.toInt();strokeWidth=2*density}
    private fun frame(id:String?)=scenes.previewLayers.lastOrNull{it.id==id}
    private fun editable(id:String)=scenes.active.layers.any{it.id==id && !it.locked}
    private fun local(f:LayerFrame,x:Float,y:Float):Pair<Float,Float>{
        val a=Math.toRadians(f.angle.toDouble())
        val dx=x-f.x*width;val dy=y-f.y*height
        return (cos(a)*dx+sin(a)*dy).toFloat() to (-sin(a)*dx+cos(a)*dy).toFloat()
    }
    fun hit(x:Float,y:Float)=scenes.previewLayers.asReversed().firstOrNull{
        val (dx,dy)=local(it,x,y)
        editable(it.id) && abs(dx)<=it.width*width/2+4*density && abs(dy)<=it.height*height/2+4*density
    }?.id
    fun onHandle(id:String?,x:Float,y:Float):Boolean{
        val f=frame(id)?:return false
        val (dx,dy)=local(f,x,y)
        return editable(f.id) && hypot(dx-f.width*width/2,dy-f.height*height/2)<18*density
    }
    private val visibleBounds = Rect()
    private val edgeBand = 24 * density
    private val edgeTravel = 32 * density
    fun atDropEdge(startX: Float, startY: Float, x: Float, y: Float): Boolean {
        if (!getLocalVisibleRect(visibleBounds)) return false
        val dx = x - startX
        val dy = y - startY
        return (x <= visibleBounds.left + edgeBand && dx <= -edgeTravel) ||
            (x >= visibleBounds.right - edgeBand && dx >= edgeTravel) ||
            (y <= visibleBounds.top + edgeBand && dy <= -edgeTravel) ||
            (y >= visibleBounds.bottom - edgeBand && dy >= edgeTravel)
    }

    override fun onDraw(canvas:Canvas){
        val f=frame(selection())
        if(f!=null && editable(f.id)){
            val w=f.width*width/2;val h=f.height*height/2
            canvas.save();canvas.translate(f.x*width,f.y*height);canvas.rotate(f.angle)
            pen.style=Paint.Style.STROKE;canvas.drawRect(-w,-h,w,h,pen)
            pen.style=Paint.Style.FILL;canvas.drawCircle(w,h,6*density,pen);canvas.restore()
        }
        if(selection()!=null)postInvalidateDelayed(50)
    }
}
