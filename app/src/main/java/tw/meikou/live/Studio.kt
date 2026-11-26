package tw.meikou.live

import android.Manifest
import android.app.*
import android.os.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.*
import android.media.*
import android.view.*
import android.widget.*
import android.graphics.Color
import java.io.File
import java.net.URI

class Studio:Activity(){
    private lateinit var preview:SurfaceView
    private lateinit var panel:LinearLayout
    private lateinit var status:TextView
    private lateinit var reading:TextView
    private lateinit var address:EditText
    private lateinit var shortcuts:LinearLayout
    private lateinit var lenses:Spinner
    private lateinit var sizes:Spinner
    private lateinit var rates:Spinner
    private lateinit var bits:Spinner
    private lateinit var codecs:Spinner
    private lateinit var zoom:SeekBar
    private lateinit var live:Button
    private lateinit var record:Button
    private var capture:Capture?=null
    private var profile:Profile?=null
    private var selected:Lens?=null
    private var ending=false
    private var ready=false
    private lateinit var inputs:Spinner
    private val handlers=Handler(Looper.getMainLooper())
    private var attempts=0
    private var waiting=false
    private var finder:Viewfinder?=null
    private var afterClose:(()->Unit)?=null
    private fun stopPreview(next:(()->Unit)?=null){
        afterClose=next
        val old=finder
        if(old==null){afterClose=null;next?.invoke()}else old.stop()
    }
    private fun previewSelected(){
        if(!ready || !visible || capture!=null || finder!=null || !granted())return
        val l=selected?:return
        val size=l.sizes.minByOrNull{kotlin.math.abs(it.width-1280)}?:return
        preview.holder.setFixedSize(size.width,size.height)
        val view=Viewfinder(this,l,preview.holder.surface,{if(visible)status.text="這個鏡頭的預覽組合無法啟動"},{
            finder=null;val next=afterClose;afterClose=null;if(visible && ready)next?.invoke()
        })
        finder=view;view.start()
    }
    private var visible=false
    private var devices=listOf<AudioDeviceInfo>()
    private fun cancelRetry(){handlers.removeCallbacksAndMessages(null);waiting=false;attempts=0}
    override fun onStart(){super.onStart();visible=true;previewSelected()}
    private var netText:TextView?=null
    private val network by lazy {getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager}
    private val monitor=object:ConnectivityManager.NetworkCallback(){
        override fun onCapabilitiesChanged(n:Network,c:NetworkCapabilities){runOnUiThread{
            netText?.text=when{c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)->"網路 · VPN";c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)->"網路 · Wi-Fi";c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)->"網路 · 行動網路";else->"網路 · 已連線"}
        }}
        override fun onLost(n:Network){runOnUiThread{netText?.text="網路 · 連線中斷"}}
    }
    override fun onCreate(b:Bundle?){super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root=LinearLayout(this).apply{setBackgroundColor(Color.rgb(12,22,25))}
        preview=SurfaceView(this)
        val frame=FrameLayout(this)
        frame.addView(preview,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        frame.addOnLayoutChangeListener{_,left,top,right,bottom,_,_,_,_->
            val width=minOf(right-left,(bottom-top)*16/9)
            val height=width*9/16
            if(width>0 && (preview.layoutParams.width!=width || preview.layoutParams.height!=height))
                preview.layoutParams=FrameLayout.LayoutParams(width,height,Gravity.CENTER)
        }
        root.addView(frame,LinearLayout.LayoutParams(0,-1,1f))
        root.setOnApplyWindowInsetsListener{v,insets->
            val padding=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(padding.left,padding.top,padding.right,padding.bottom);insets
        }
        val scroll=ScrollView(this);panel=LinearLayout(this).apply{orientation=1;setPadding(16,12,16,16)}
        scroll.addView(panel);root.addView(scroll,LinearLayout.LayoutParams((resources.displayMetrics.density*265).toInt(),-1))
        setContentView(root)
        val mark=ImageView(this);mark.setImageResource(resources.getIdentifier("wordmark","drawable",packageName));mark.adjustViewBounds=true;panel.addView(mark,-1,(resources.displayMetrics.density*64).toInt())
        status=label(ui("idle"));status.setTextColor(Color.rgb(99,226,202))
        netText=label("網路 · 檢查中");reading=label("尚未擷取")
        shortcuts=LinearLayout(this);panel.addView(shortcuts)
        lenses=spinner(ui("lens"));sizes=spinner(ui("resolution"));rates=spinner(ui("fps"));bits=spinner(ui("bitrate"))
        options(bits,listOf(2,4,6,8,12));bits.setSelection(1)
        inputs=spinner(ui("input_device"))
        val sound=getSystemService(AUDIO_SERVICE) as AudioManager
        devices=sound.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        options(inputs,listOf(ui("system_default"))+devices.map{it.productName.toString()+" · "+it.id})
        codecs=spinner(ui("codec"));options(codecs,listOf("H.264","H.265（限錄影）"))
        val zoomLabel=label("系統變焦 1.0×")
        zoom=SeekBar(this);zoom.max=100;panel.addView(zoom)
        zoom.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(s:SeekBar,p:Int,user:Boolean){selected?.let{l->
                val factor=zoomRatio(l,p);zoomLabel.text="系統變焦 %.2f×".format(factor)
                if(user){capture?.changeZoom(factor);finder?.zoom(factor)}
            }}
            override fun onStartTrackingTouch(s:SeekBar){}
            override fun onStopTrackingTouch(s:SeekBar){}
        })
        address=EditText(this).apply{hint="RTMP／RTMPS 完整推流網址";setSingleLine();inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;isSaveEnabled=false}
        panel.addView(address)
        live=button(ui("go_live")){begin(true)}
        record=button(ui("record")){begin(false)}
        button(ui("diagnostics")){hardware()}
        lenses.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(v:AdapterView<*>?){}
            override fun onItemSelected(v:AdapterView<*>?,view:View?,i:Int,id:Long){
                val next=lenses.selectedItem as? Lens?:return
                if(selected==next)return
                val running=profile
                if(running!=null && (next.sizes.none{it.width==running.width && it.height==running.height} || running.fps !in next.rates)){
                    status.text="這顆鏡頭不支援目前規格，請停止後再更改。";lenses.setSelection((0 until lenses.count).first{lenses.getItemAtPosition(it)==selected});return
                }
                selected=next
                if(running==null){options(sizes,next.sizes);sizes.setSelection(next.sizes.indexOfFirst{it.width==1280}.coerceAtLeast(0))
                    options(rates,next.rates);rates.setSelection(next.rates.indexOf(30).coerceAtLeast(0))}
                zoom.isEnabled=next.zoomMax>next.zoomMin;zoom.progress=(0..100).minByOrNull{kotlin.math.abs(zoomRatio(next,it)-1f)}?:0
                zoomLabel.text="系統變焦 1.00×"
                if(capture!=null)capture?.changeLens(next) else stopPreview{previewSelected()}
            }
        }
        preview.holder.addCallback(object:SurfaceHolder.Callback{
            override fun surfaceCreated(h:SurfaceHolder){ready=true;previewSelected()}
            override fun surfaceChanged(h:SurfaceHolder,f:Int,w:Int,t:Int){}
            override fun surfaceDestroyed(h:SurfaceHolder){ready=false;stopPreview();capture?.stop()}
        })
        network.registerDefaultNetworkCallback(monitor)
        if(granted())loadLenses() else requestPermissions(arrayOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO),1)
    }
    private fun zoomRatio(l:Lens,p:Int):Float=if(p<=25 && l.zoomMin<1f) l.zoomMin+(1f-l.zoomMin)*p/25 else if(l.zoomMin<1f)1f+(l.zoomMax-1f)*(p-25)/75 else 1f+(l.zoomMax-1f)*p/100
    private fun ui(key:String)=getString(resources.getIdentifier(key,"string",packageName))
    override fun attachBaseContext(base:Context){
        val config=android.content.res.Configuration(base.resources.configuration)
        config.setLocale(java.util.Locale.TAIWAN)
        super.attachBaseContext(base.createConfigurationContext(config))
    }
    private fun label(s:String)=TextView(this).also{it.text=s;panel.addView(it)}
    private fun spinner(s:String)=Spinner(this).also{label(s);panel.addView(it)}
    private fun button(s:String,f:()->Unit)=Button(this).also{it.text=s;panel.addView(it);it.setOnClickListener{f()}}
    private fun <T> options(view:Spinner,items:List<T>){view.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,items)}
    private fun granted()=listOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO).all{checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED}
    override fun onRequestPermissionsResult(r:Int,p:Array<String>,g:IntArray){super.onRequestPermissionsResult(r,p,g);if(granted())loadLenses()else status.text="請在系統設定允許使用相機與麥克風。"}
    private fun loadLenses(){runCatching{val list=inventory(getSystemService(CAMERA_SERVICE) as CameraManager);options(lenses,list)
        shortcuts.removeAllViews()
        val groups=list.withIndex().filter{!it.value.front && it.value.equivalent!=null}.groupBy{
            val mm=it.value.equivalent!!
            when{mm<22->ui("wide");mm<50->ui("normal");else->ui("tele")}
        }
        for(name in listOf(ui("wide"),ui("normal"),ui("tele"))){
            val group=groups[name]?:continue
            val candidate=group.sortedByDescending{it.value.physical!=null}.first()
            shortcuts.addView(Button(this).apply{text=name;setPadding(0,0,0,0);minWidth=0
                setOnClickListener{lenses.setSelection(candidate.index)}},LinearLayout.LayoutParams(0,-2,1f))
        }
        if(list.isEmpty())status.text="無可用鏡頭"}.onFailure{status.text="無法讀取鏡頭參數"}}
    private fun hardware(){
        val a=getSystemService(AUDIO_SERVICE) as AudioManager
        val text=buildString{appendLine(Build.MANUFACTURER+" "+Build.MODEL+" · Android "+Build.VERSION.RELEASE)
            val manager=getSystemService(CAMERA_SERVICE) as CameraManager
            for(id in manager.cameraIdList){
                val parent=manager.getCameraCharacteristics(id)
                appendLine("公開相機 "+id+" · 相依實體 ID "+parent.physicalCameraIds.sorted().joinToString())
                for(sensor in listOf(id)+parent.physicalCameraIds.sorted()){
                    val info=runCatching{manager.getCameraCharacteristics(sensor)}.getOrNull()?:continue
                    appendLine("ID "+sensor+" · 實焦 "+info[android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.joinToString()+" mm · 感光元件 "+info[android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]+" · 方向 "+info[android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION]+"°")
                }
            }
            appendLine("支援本版輸出格式的候選鏡頭")
            for(i in 0 until lenses.count){val l=lenses.getItemAtPosition(i) as Lens
                appendLine(l.toString()+" · 母相機 "+l.parent);appendLine("焦距 "+l.focal.joinToString()+" mm · 感光元件寬 "+(l.sensorWidth?:"—")+" mm")
                appendLine("變焦 "+l.zoomMin+"–"+l.zoomMax+"× · FPS "+l.rates.joinToString());appendLine(l.sizes.joinToString())}
            appendLine("可用音訊輸入");a.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach{appendLine(it.productName.toString()+" · "+it.id)}
            appendLine("影片編碼器");MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter{it.isEncoder && it.supportedTypes.any{t->t=="video/avc" || t=="video/hevc"}}.forEach{appendLine(it.name+" · "+if(it.isHardwareAccelerated)"硬體" else "軟體")}
        }
        AlertDialog.Builder(this).setTitle(ui("hardware")).setMessage(text).setNeutralButton(ui("copy")){_,_->(getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("Meikou Live",text))}.setPositiveButton(ui("close"),null).show()
    }
    private fun begin(stream:Boolean){
        if(waiting){cancelRetry();status.text="已取消重新連線";live.text=ui("go_live");return}
        if(capture!=null){ending=true;capture?.stop();status.text="正在停止";return}
        if(!ready || !granted()){status.text="請確認預覽區及相機、麥克風權限";return}
        val l=selected?:return
        val size=sizes.selectedItem as? android.util.Size?:return
        val fps=rates.selectedItem as? Int?:return
        val p=Profile(size.width,size.height,fps,(bits.selectedItem as Int)*1000000,codecs.selectedItemPosition==1)
        val uri=if(stream)runCatching{URI(address.text.toString().trim()).also{require(it.scheme in listOf("rtmp","rtmps") && it.host!=null && it.rawPath.trim('/').contains('/') && !it.rawPath.endsWith('/') && it.userInfo==null)}}.getOrNull() else null
        if(stream && (uri==null || p.hevc)){status.text="直播請選用 H.264，並輸入含串流金鑰的完整網址。";return}
        if(finder!=null){stopPreview{begin(stream)};return}
        val folder=getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        if(!stream && folder==null){status.text="無法使用錄影資料夾";return}
        val file=if(stream)null else File(folder,"Meikou-"+System.currentTimeMillis()+".mp4")
        preview.holder.setFixedSize(p.width,p.height)
        ending=false;profile=p;status.text="正在啟動影音擷取"
        listOf(inputs,sizes,rates,bits,codecs,address).forEach{it.isEnabled=false}
        live.isEnabled=stream;record.isEnabled=!stream
        if(stream)live.text=ui("stop_live") else record.text=ui("stop_record")
        capture=Capture(this,preview.holder.surface,p,l,devices.getOrNull(inputs.selectedItemPosition-1),zoomRatio(l,zoom.progress),{s->runOnUiThread{reading.text=s}},uri,file,
            {mode->runOnUiThread{if(!ending)status.text=if(mode==1)"直播中 · 已送出影音" else "錄影中"}},
            {ok,networkError->
                val saved=if(ok && file!=null)saveMovie(file) else null
                runOnUiThread{capture=null;profile=null
                listOf(inputs,sizes,rates,bits,codecs,address,live,record).forEach{it.isEnabled=true}
                live.text=ui("go_live");record.text=ui("record");reading.text="已停止擷取"
                status.text=if(!ok)"擷取或傳輸未完成，請檢查鏡頭、編碼規格與連線。" else saved?.let{"已儲存 "+it} ?: "直播已結束"
                if(stream && networkError && !ending && visible && attempts<3){
                    attempts++;waiting=true;val delay=1000L shl attempts
                    status.text="連線中斷，"+(delay/1000)+" 秒後重試（"+attempts+"/3）"
                    live.text="取消重新連線"
                    handlers.postDelayed({waiting=false;if(visible && capture==null)begin(true)},delay)
                }else{attempts=0;previewSelected()}
            }}).also{it.start()}
    }
    private fun saveMovie(file:File):String{
        val values=ContentValues().apply{
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,file.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"video/mp4")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Movies/Meikou Live")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING,1)
        }
        val uri=runCatching{contentResolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values)}.getOrNull()?:return file.absolutePath
        return try{
            file.inputStream().use{input->requireNotNull(contentResolver.openOutputStream(uri)).use{input.copyTo(it)}}
            values.clear();values.put(android.provider.MediaStore.MediaColumns.IS_PENDING,0)
            check(contentResolver.update(uri,values,null,null)==1)
            file.delete()
            "Movies/Meikou Live/"+file.name
        }catch(e:Exception){runCatching{contentResolver.delete(uri,null,null)};file.absolutePath}
    }
    override fun onStop(){super.onStop();visible=false;stopPreview();cancelRetry();ending=true;capture?.stop()}
    override fun onDestroy(){network.unregisterNetworkCallback(monitor);super.onDestroy()}
}

