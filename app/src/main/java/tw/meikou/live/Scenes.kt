package tw.meikou.live

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Overlay(val id:String=UUID.randomUUID().toString(),val kind:String,val value:String,
    val x:Float=.5f,val y:Float=.5f,val width:Float=.35f,val angle:Float=0f,val locked:Boolean=false,
    val bitmap:Bitmap?=null,val items:List<String> = emptyList(),val slideSeconds:Int=5)
data class Scene(val id:String=UUID.randomUUID().toString(),val name:String,val layers:List<Overlay> = emptyList())
data class LayerFrame(val id:String,val x:Float,val y:Float,val width:Float,val height:Float,val angle:Float)

class Scenes(private val ctx:Context){
    @Volatile var previewLayers:List<LayerFrame> = emptyList()
    private val prefs=ctx.getSharedPreferences("scene-v1",Context.MODE_PRIVATE)
    @Volatile var mirrorSelfie=false
    @Volatile var active=Scene(name="現場")
        private set
    private var all=listOf(active)
    init{
        runCatching{
            val json=JSONObject(prefs.getString("library","{}")!!)
            val list=json.getJSONArray("scenes")
            val loaded=(0 until list.length()).map{n->
                val scene=list.getJSONObject(n)
                val layers=scene.getJSONArray("layers")
                Scene(scene.getString("id"),scene.getString("name"),(0 until layers.length()).map{i->
                    val v=layers.getJSONObject(i)
                    val kind=v.getString("kind");val value=v.getString("value")
                    Overlay(v.getString("id"),kind,value,v.getDouble("x").toFloat(),v.getDouble("y").toFloat(),
                        v.getDouble("width").toFloat(),v.getDouble("angle").toFloat(),v.optBoolean("locked"),
                        if(kind=="image")runCatching{decode(Uri.parse(value))}.getOrNull() else null,
                        v.optJSONArray("items")?.let{items->(0 until items.length()).map{items.getString(it)}}.orEmpty(),
                        v.optInt("slideSeconds",5).coerceIn(1,120))
                })
            }
            if(loaded.isNotEmpty()){all=loaded;active=all.firstOrNull{it.id==json.optString("active")}?:all.first()}
        }
    }
    fun decode(uri:Uri):Bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver,uri)){decoder,info,_->
        val scale=minOf(1f,1280f/maxOf(info.size.width,info.size.height))
        decoder.setTargetSize(maxOf(1,(info.size.width*scale).toInt()),maxOf(1,(info.size.height*scale).toInt()))
        decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
    }
    fun scenes()=all
    fun select(scene:Scene){active=scene;save()}
    fun create(name:String){val scene=Scene(name=name);all=all+scene;select(scene)}
    fun layers(layers:List<Overlay>,persist:Boolean=true){
        active=active.copy(layers=layers);all=all.map{if(it.id==active.id)active else it}
        if(persist)save()
    }
    fun save(){
        val array=JSONArray()
        all.forEach{s->
            val layers=JSONArray()
            s.layers.forEach{v->layers.put(JSONObject().put("id",v.id).put("kind",v.kind).put("value",v.value)
                .put("x",v.x.toDouble()).put("y",v.y.toDouble()).put("width",v.width.toDouble()).put("angle",v.angle.toDouble()).put("locked",v.locked).put("items",JSONArray(v.items)).put("slideSeconds",v.slideSeconds))}
            array.put(JSONObject().put("id",s.id).put("name",s.name).put("layers",layers))
        }
        prefs.edit().putString("library",JSONObject().put("version",1).put("active",active.id).put("scenes",array).toString()).apply()
    }
}
