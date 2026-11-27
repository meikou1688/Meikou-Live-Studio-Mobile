package tw.meikou.live

import android.app.*
import android.content.Intent
import android.net.Uri
import android.view.*
import android.widget.*
import kotlin.math.*

class SceneEditor(private val host:Activity,private val scenes:Scenes,private val preview:LayerCanvas,private val removeAction:Button,private val cleanPreview:(Boolean)->Unit,private val canPick:()->Boolean){
    companion object{const val PICK=701;const val PICK_VIDEO=702;const val PICK_SLIDES=706;const val PICK_CLIPS=707;const val PICK_MUSIC=708;const val PICK_GREEN=709;const val PICK_BLUE=710}
    private var selected:String?=null
    private var previousX=0f
    private var previousY=0f
    private var previousSpan=0f
    private var previousAngle=0f
    private var count=0
    private var handleScale=false
    private var blank=false
    private var startX=0f
    private var startY=0f
    private val touchSlop = ViewConfiguration.get(host).scaledTouchSlop
    private var dragged = false
    private var multiplePointers = false

    fun clearSelection() {
        selected = null
        blank = false
        removeAction.visibility = View.GONE
        preview.invalidate()
    }

    private fun remove(id: String) {
        scenes.layers(scenes.active.layers.filterNot { it.id == id })
        clearSelection()
    }

    private fun showRemoveAction(atEdge: Boolean) {
        removeAction.setText(if (atEdge) R.string.release_to_remove else R.string.remove_overlay)
        removeAction.isClickable = !atEdge
        removeAction.visibility = View.VISIBLE
    }

    init{
        removeAction.setOnClickListener {
            scenes.active.layers.firstOrNull { it.id == selected && !it.locked }?.let { remove(it.id) }
        }
        preview.selection={selected}
        preview.setOnTouchListener{_,event->gesture(event)}
    }
    private fun toast(text:String){Toast.makeText(host,text,Toast.LENGTH_SHORT).show()}
    private fun prompt(title:String,initial:String="",apply:(String)->Unit){
        val text=EditText(host).apply{setSingleLine();setText(initial);filters=arrayOf(android.text.InputFilter.LengthFilter(100))}
        AlertDialog.Builder(host).setTitle(title).setView(text).setPositiveButton("確定"){_,_->text.text.toString().trim().takeIf{it.isNotEmpty()}?.let(apply)}.setNegativeButton("取消",null).show()
    }
    fun effects(){
        val effects=arrayOf("原色","黑白","暖色","冷色","綠幕換背景","藍幕換背景")
        AlertDialog.Builder(host).setTitle("鏡頭效果").setSingleChoiceItems(effects,scenes.active.cameraEffect){dialog,index->
            dialog.dismiss()
            if(index<4)scenes.cameraEffect(index)
            else if(canPick())host.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),
                if(index==4)PICK_GREEN else PICK_BLUE)
            else if(scenes.active.layers.any{it.kind=="backdrop"})scenes.cameraEffect(index)
            else toast("請在開播前先選擇背景圖片")
        }.show()
    }
    fun backdrop(uri:Uri,green:Boolean){
        runCatching{
            host.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val image=scenes.decode(uri)
            scenes.layers(scenes.active.layers.filterNot{it.kind=="backdrop"}+
                Overlay(kind="backdrop",value=uri.toString(),bitmap=image,locked=true))
            scenes.cameraEffect(if(green)4 else 5)
        }.onFailure{toast("無法讀取背景圖片")}
    }
    fun chooseScene(){
        val list=scenes.scenes()
        AlertDialog.Builder(host).setTitle("場景 · "+scenes.active.name)
            .setItems((list.map{it.name}+"新增場景").toTypedArray()){_,i->
                clearSelection()
                if(i==list.size)prompt("場景名稱"){scenes.create(it)} else scenes.select(list[i])
            }.show()
    }
    private fun add(v:Overlay){
        clearSelection()
        scenes.layers(scenes.active.layers+v)
        selected=v.id
        preview.invalidate()
        toast(host.getString(R.string.overlay_gestures))
    }
    fun media(uri:Uri,video:Boolean){
        try{
            host.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if(video){
                val probe=android.media.MediaMetadataRetriever()
                try{probe.setDataSource(host,uri);require(probe.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)=="yes")}
                finally{probe.release()}
                add(Overlay(kind="video",value=uri.toString()))
            }else add(Overlay(kind="image",value=uri.toString(),bitmap=scenes.decode(uri)))
        }catch(e:Exception){toast("無法讀取素材，請重新選擇")}
    }
    fun playlist(data:Intent,type:Int){
        val selected=buildList{
            val clips=data.clipData
            if(clips!=null)for(i in 0 until clips.itemCount)add(clips.getItemAt(i).uri)
            else data.data?.let{add(it)}
        }.distinct()
        if(selected.isEmpty())return
        try{
            selected.forEach{host.contentResolver.takePersistableUriPermission(it,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
            val items=selected.map{it.toString()}
            if(type==PICK_MUSIC){
                scenes.layers(scenes.active.layers.filterNot{it.kind=="music"}+Overlay(kind="music",value="",items=items))
                toast("已套用場景背景音樂，錄影或直播時播放")
            }else if(type==PICK_CLIPS)add(Overlay(kind="clips",value="",items=items))
            else android.app.AlertDialog.Builder(host).setTitle("每張圖片停留時間").setItems(arrayOf("3 秒","5 秒","10 秒","30 秒")){_,i->
                add(Overlay(kind="slides",value="",items=items,slideSeconds=listOf(3,5,10,30)[i]))
            }.show()
        }catch(e:Exception){toast("無法取得素材的讀取權限")}
    }
    private fun title(v:Overlay)=when(v.kind){"image"->"圖片／台標";"video"->"影片（靜音循環）";"clock"->"時鐘";"music"->"背景音樂播放清單";"slides"->"圖片播放清單";"clips"->"影片播放清單（靜音）";"date"->"日期";"qr"->"QR Code";"score"->"比分板";"ticker"->"跑馬燈 · "+v.value;else->"文字 · "+v.value}
    fun edit(){
        removeAction.visibility = View.GONE
        val layers=scenes.active.layers.filterNot{it.kind=="backdrop"}.reversed()
        val choices=listOf("新增文字","新增圖片／台標","新增跑馬燈","新增時鐘","新增影片（靜音循環）","新增日期","新增 QR Code","新增比分板","新增圖片播放清單","新增影片播放清單（靜音）","選擇場景背景音樂")+layers.map{title(it)}
        AlertDialog.Builder(host).setTitle("素材圖層 · "+scenes.active.name).setItems(choices.toTypedArray()){_,i->
            when(i){
                0->prompt("文字內容"){add(Overlay(kind="text",value=it))}
                1->if(canPick())host.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),PICK) else toast("請於開播前匯入圖片，直播中可切換已儲存的場景。")
                2->prompt("跑馬燈文字"){add(Overlay(kind="ticker",value=it,y=.85f,width=.6f))}
                3->add(Overlay(kind="clock",value="",x=.8f,y=.12f,width=.23f))
                4->if(canPick())host.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE),PICK_VIDEO) else toast("請於開播前匯入影片")
                5->add(Overlay(kind="date",value="",x=.8f,y=.2f,width=.23f))
                6->prompt("QR Code 網址或文字"){add(Overlay(kind="qr",value=it,width=.18f))}
                7->prompt("左隊隊名"){left->prompt("右隊隊名"){right->
                    add(Overlay(kind="score",value=org.json.JSONArray().put(left).put(right).put(0).put(0).toString(),y=.14f))
                }}
                8,9,10->if(canPick()){
                    val chooser=Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(when(i){8->"image/*";9->"video/*";else->"audio/*"}).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
                    host.startActivityForResult(chooser,when(i){8->PICK_SLIDES;9->PICK_CLIPS;else->PICK_MUSIC})
                }else toast("請於開播前匯入播放清單")
                else->actions(layers[i-11])
            }
        }.show()
    }
    private fun modify(v:Overlay){scenes.layers(scenes.active.layers.map{if(it.id==v.id)v else it})}
    private fun actions(v:Overlay){
        if(v.kind=="music"){
            val menu=listOf("靜音","音量 25%","音量 50%","音量 75%","音量 100%","移除背景音樂")
            android.app.AlertDialog.Builder(host).setTitle("背景音樂 · 音量 "+v.volume+"%").setItems(menu.toTypedArray()){_,index->
                if(index==5)scenes.layers(scenes.active.layers.filterNot{it.id==v.id})
                else modify(v.copy(volume=index*25))
            }.show()
            return
        }
        val opts=mutableListOf("選取並調整位置",if(v.locked)"解除鎖定" else "鎖定圖層","移到最上層","移到最下層","移除")
        if(v.kind=="text" || v.kind=="ticker")opts.add("修改文字")
        if(v.kind=="score")opts.addAll(listOf("左隊加一分","右隊加一分","左隊減一分","右隊減一分"))
        AlertDialog.Builder(host).setTitle(title(v)).setItems(opts.toTypedArray()){_,i->
            val rest=scenes.active.layers.filterNot{it.id==v.id}
            when(i){
                0->{selected=v.id;preview.invalidate();toast(if(v.locked)"請先解除鎖定" else "拖曳移動，雙指縮放及旋轉")}
                1->modify(v.copy(locked=!v.locked))
                2->scenes.layers(rest+v)
                3->scenes.layers(listOf(v)+rest)
                4->remove(v.id)
                in 5..8->if(v.kind=="score"){
                    val scores=org.json.JSONArray(v.value)
                    val side=if(i==5 || i==7)2 else 3
                    scores.put(side,(scores.getInt(side)+if(i<=6)1 else -1).coerceAtLeast(0))
                    modify(v.copy(value=scores.toString()))
                }else prompt("修改文字",v.value){modify(v.copy(value=it))}
            }
        }.show()
    }
    private fun gesture(e:MotionEvent):Boolean{
        if(e.actionMasked==MotionEvent.ACTION_DOWN){
            count=0
            dragged = false
            multiplePointers = false
            removeAction.visibility = View.GONE
            val handle=preview.onHandle(selected,e.x,e.y)
            handleScale=handle
            if(!handle)selected=preview.hit(e.x,e.y)
            blank=selected==null
            startX=e.x;startY=e.y
            preview.invalidate()
        }
        if(blank){
            if(e.actionMasked==MotionEvent.ACTION_POINTER_DOWN || e.actionMasked==MotionEvent.ACTION_CANCEL)blank=false
            if(e.actionMasked==MotionEvent.ACTION_UP){
                blank=false
                val dx=e.x-startX;val dy=e.y-startY
                if(abs(dx)>64*host.resources.displayMetrics.density && abs(dx)>1.5f*abs(dy)){
                    if(dx<0){cleanPreview(false);edit()} else cleanPreview(true)
                }else cleanPreview(false)
            }
            return true
        }
        val layer=scenes.active.layers.firstOrNull{it.id==selected && !it.locked}?:return false
        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            multiplePointers = true
            removeAction.visibility = View.GONE
        }
        val singleMove = !handleScale && !multiplePointers
        if (hypot(e.x - startX, e.y - startY) > touchSlop) dragged = true
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            count = 0
            removeAction.visibility = View.GONE
            if (e.actionMasked == MotionEvent.ACTION_UP && !multiplePointers) {
                if (singleMove && dragged && preview.atDropEdge(startX, startY, e.x, e.y)) {
                    remove(layer.id)
                    return true
                }
                if (!dragged && (!handleScale || preview.hit(e.x, e.y) == layer.id)) {
                    showRemoveAction(atEdge = false)
                }
            }
            if (dragged || multiplePointers) scenes.save()
            return true
        }
        if (e.actionMasked == MotionEvent.ACTION_MOVE && singleMove) {
            if (!dragged) return true
            if (preview.atDropEdge(startX, startY, e.x, e.y)) showRemoveAction(atEdge = true)
            else removeAction.visibility = View.GONE
        }
        val n=e.pointerCount.coerceAtMost(2)
        val x=if(n==1)e.x else (e.x+e.getX(1))/2
        val y=if(n==1)e.y else (e.y+e.getY(1))/2
        val dx=if(n==2)e.getX(1)-e.x else 0f
        val dy=if(n==2)e.getY(1)-e.y else 0f
        val span=if(n==1 && handleScale)hypot(x-layer.x*preview.width,y-layer.y*preview.height) else hypot(dx,dy)
        val angle=Math.toDegrees(atan2(dy,dx).toDouble()).toFloat()
        if(e.actionMasked==MotionEvent.ACTION_MOVE && count==n && preview.width>0 && preview.height>0){
            val move=!(handleScale && n==1)
            val next=layer.copy(x=if(!move)layer.x else (layer.x+(x-previousX)/preview.width).coerceIn(0f,1f),y=if(!move)layer.y else (layer.y+(y-previousY)/preview.height).coerceIn(0f,1f),
                width=(layer.width*(if((n==2 || handleScale) && previousSpan>1f)span/previousSpan else 1f)).coerceIn(.03f,2f),
                angle=if(n==2)(layer.angle+angle-previousAngle)%360f else layer.angle)
            scenes.layers(scenes.active.layers.map{if(it.id==layer.id)next else it},false)
        }
        previousX=x;previousY=y;previousSpan=span;previousAngle=angle;count=if(e.actionMasked==MotionEvent.ACTION_POINTER_UP)0 else n
        return true
    }
}
