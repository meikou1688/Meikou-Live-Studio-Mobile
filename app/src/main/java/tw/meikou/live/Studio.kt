package tw.meikou.live

import android.Manifest
import android.app.*
import android.os.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.net.*
import android.media.*
import android.view.*
import android.widget.*
import android.graphics.Color
import java.io.File
import java.net.URI

class Studio:Activity(){
    private fun dp(value:Int)=(value*resources.displayMetrics.density+0.5f).toInt()
    private fun color(hex:String)=Color.parseColor(hex)
    private fun box(fill:String,edge:String=fill,radius:Int=6)=android.graphics.drawable.GradientDrawable().apply{
        setColor(color(fill));setStroke(dp(1),color(edge));cornerRadius=dp(radius).toFloat()
    }
    private lateinit var monitorArea:FrameLayout
    private lateinit var monitorName:TextView
    private fun monitorState(streaming:Boolean){
        val shade=if(streaming)"#ff4d5e" else "#2ecc71"
        monitorArea.background=box("#000000",shade,4)
        monitorArea.foreground=android.graphics.drawable.GradientDrawable().apply{setColor(Color.TRANSPARENT);setStroke(dp(2),color(shade))}
        monitorName.text=if(streaming)"播出 PGM" else "預覽 PVW"
        monitorName.setTextColor(color(shade))
    }
    private fun section(title:String){
        label(title).apply{
            setTextColor(color("#00e5c0"));textSize=15f
            setTypeface(typeface,android.graphics.Typeface.BOLD)
            setPadding(0,dp(14),0,dp(3))
        }
    }
    private val scenes by lazy{Scenes(this)}
    private val portrait get()=resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT
    private lateinit var rotateButton:Button
    private lateinit var sceneEditor:SceneEditor
    private lateinit var preview:SurfaceView
    private lateinit var panel:LinearLayout
    private lateinit var status:TextView
    private lateinit var reading:TextView
    private lateinit var address:EditText
    private lateinit var streamKey:EditText
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
    private lateinit var backgroundLive:CheckBox
    private var reduceNoise:CheckBox?=null
    private var backgroundActive=false
    private var backgroundPreparing=false
    private var profile:Profile?=null
    private var selected:Lens?=null
    private var ending=false
    private var ready=false
    private var additional=listOf<URI>()
    private lateinit var outputSummary:TextView
    private val outputStates=sortedMapOf<Int,String>()
    private val youtube by lazy { YouTubeChannel(this) { status.text=it } }
    private lateinit var editOutputs:Button
    private var extraFields=listOf<Pair<String,String>>()
    private fun destinationField(hintText:String,hide:Boolean)=EditText(this).apply{
        hint=hintText;setSingleLine();isSaveEnabled=false
        importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO
        inputType=android.text.InputType.TYPE_CLASS_TEXT or (if(hide)android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_URI)
    }
    private fun editDestinations(){
        val form=LinearLayout(this).apply{orientation=1;setPadding(dp(12),0,dp(12),0)}
        val rows=(0 until 5).map{i->
            form.addView(TextView(this).apply{text="目的地 "+(i+2)})
            val url=destinationField("伺服器網址或完整推流網址",false)
            val key=destinationField("串流金鑰",true)
            extraFields.getOrNull(i)?.let{url.setText(it.first);key.setText(it.second)}
            form.addView(url);form.addView(key);url to key
        }
        val dialog=AlertDialog.Builder(this).setTitle("額外串流目的地")
            .setView(ScrollView(this).apply{addView(form)}).setPositiveButton("儲存",null).setNegativeButton("取消",null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val fields=mutableListOf<Pair<String,String>>()
            val urls=mutableListOf<URI>()
            for((url,key) in rows){
                val server=url.text.toString().trim();val secret=key.text.toString().trim()
                if(server.isEmpty() && secret.isEmpty())continue
                val target=runCatching{streamDestination(server,secret)}.getOrNull()
                if(target==null || target in urls){url.error="請確認網址、金鑰及重複目的地";url.requestFocus();return@setOnClickListener}
                fields.add(server to secret);urls.add(target)
            }
            extraFields=fields;additional=urls;outputSummary.text="額外目的地："+urls.size;dialog.dismiss()
        }
    }
    private lateinit var saveLive:CheckBox
    private lateinit var mute:Switch
    private lateinit var inputs:Spinner
    private val handlers=Handler(Looper.getMainLooper())
    private var attempts=0
    private var waiting=false
    private var exposureCompensation=0
    private var settings=CameraSettings()
    private fun applySettings(value:CameraSettings){settings=value;finder?.configure(value);capture?.configure(value)}
    private fun choose(title:String,items:List<String>,select:(Int)->Unit){
        AlertDialog.Builder(this).setTitle(title).setItems(items.toTypedArray()){_,index->select(index)}.show()
    }
    private fun professional(){
        val lens=selected?:return
        val info=(getSystemService(CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(lens.parent)
        val focus=info[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE]?:0f
        val wb=(info[CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES] ?: intArrayOf()).filter{it>0}
        val iso=info[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]
        val times=info[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]
        val manual=info[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)==true
        val menu=mutableListOf<Pair<String,()->Unit>>()
        if(focus>0 && info[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES]?.contains(CameraMetadata.CONTROL_AF_MODE_OFF)==true){
            menu.add("對焦距離" to {
                val steps=listOf<Float?>(null,0f,focus/4,focus/2,focus*3/4,focus)
                choose("對焦距離",steps.map{when(it){null->"自動";0f->"無限遠";else->"約 %.2f 公尺".format(1f/it)}}){applySettings(settings.copy(focus=steps[it]))}
            })
        }
        val stabilization=info[CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES] ?: intArrayOf()
        if(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in stabilization){
            menu.add("電子防手震" to {
                choose("電子防手震",listOf("依系統設定","開啟","關閉")){ index ->
                    applySettings(settings.copy(stabilization=listOf(null,CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON,CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)[index]))
                }
            })
        }
        if(wb.isNotEmpty())menu.add("白平衡" to {
            val labels=listOf("關閉","自動","白熾燈","螢光燈","暖色螢光燈","晴天","陰天","黃昏","陰影")
            choose("白平衡",wb.map{labels.getOrElse(it){"模式 "+it}}){applySettings(settings.copy(whiteBalance=wb[it]))}
        })
        if(manual && iso!=null && times!=null)menu.add("感光度與快門" to {
            val values=(listOf(iso.lower)+listOf(50,100,200,400,800,1600,3200,6400).filter{iso.contains(it)}+iso.upper).distinct()
            choose("ISO 感光度",listOf("自動曝光")+values.map{"ISO "+it}){i->
                if(i==0)applySettings(settings.copy(iso=null,exposureNs=null))
                else{
                    val fps=profile?.fps ?: (rates.selectedItem as? Int ?:30)
                    val maximum=minOf(times.upper,1_000_000_000L/fps)
                    val durations=(listOf(maximum)+listOf(60,100,125,250,500,1000,2000).map{1_000_000_000L/it}).filter{times.contains(it) && it<=maximum}.distinct()
                    if(durations.isNotEmpty())choose("快門時間",durations.map{"1/"+(1_000_000_000L/it)+" 秒"}){j->applySettings(settings.copy(iso=values[i-1],exposureNs=durations[j]))}
                }
            }
        })
        menu.add("還原相機自動設定" to {applySettings(CameraSettings());exposureCompensation=0;finder?.exposure(0);capture?.changeExposure(0)})
        choose("專業相機",menu.map{it.first}){menu[it].second.invoke()}
    }
    private fun selectExposure(){
        val lens=selected?:return
        val manager=getSystemService(CAMERA_SERVICE) as CameraManager
        val info=manager.getCameraCharacteristics(lens.parent)
        val range=info[android.hardware.camera2.CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE]?:return
        val step=info[android.hardware.camera2.CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP]?.toFloat()?:return
        if(range.lower==range.upper || step<=0){Toast.makeText(this,ui("ev_unsupported"),Toast.LENGTH_SHORT).show();return}
        val indices=(range.lower..range.upper).toList()
        val labels=indices.map{String.format(java.util.Locale.TAIWAN,"%+.2f EV",it*step)}.toTypedArray()
        AlertDialog.Builder(this).setTitle(ui("ev")).setSingleChoiceItems(labels,indices.indexOf(exposureCompensation)){dialog,index->
            exposureCompensation=indices[index]
            finder?.exposure(exposureCompensation)
            capture?.changeExposure(exposureCompensation)
            dialog.dismiss()
        }.show()
    }
    private var finder:Viewfinder?=null
    private var afterClose:(()->Unit)?=null
    private fun stopPreview(next:(()->Unit)?=null){
        afterClose=next
        val old=finder
        if(old==null){afterClose=null;next?.invoke()}else old.stop()
    }
    private fun fallback(l:Lens){
        if(selected!=l)return
        val main=(0 until lenses.count).firstOrNull{val item=lenses.getItemAtPosition(it) as Lens; !item.front && item.physical==null}?:return
        if(lenses.getItemAtPosition(main)==l){status.text="主鏡頭無法啟動";return}
        status.text="此鏡頭無法啟動，切回主鏡頭"
        lenses.setSelection(main)
    }
    private fun previewSelected(){
        if(ScreenService.running || !ready || !visible || capture!=null || finder!=null || !granted())return
        val l=selected?:return
        val size=l.sizes.minByOrNull{kotlin.math.abs(it.width-1280)}?:return
        val w=if(portrait)size.height else size.width
        val h=if(portrait)size.width else size.height
        val view=Viewfinder(this,l,preview.holder.surface,w,h,scenes,settings,{reading.text=it},{if(visible)fallback(l)},{
            finder=null;val next=afterClose;afterClose=null;if(visible && ready)next?.invoke()
        })
        finder=view;view.exposure(exposureCompensation);view.start()
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
        val page=LinearLayout(this).apply{orientation=1;setBackgroundColor(color("#12131a"))}
        val bar=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),0,dp(12),0)}
        page.addView(bar,-1,dp(36))
        val logo=ImageView(this).apply{
            setImageResource(resources.getIdentifier("wordmark","drawable",packageName))
            scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription="Meikou Live Studio"
        }
        bar.addView(logo,dp(if(portrait)80 else 115),dp(28))
        monitorName=TextView(this).apply{gravity=Gravity.CENTER_VERTICAL or Gravity.END;textSize=14f}
        bar.addView(monitorName,LinearLayout.LayoutParams(0,-1,1f))
        val root=FrameLayout(this)
        page.addView(root,LinearLayout.LayoutParams(-1,0,1f))
        preview=SurfaceView(this)
        val frame=FrameLayout(this);monitorArea=frame;monitorState(false)
        frame.addView(preview,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        val touch=LayerCanvas(this,scenes)
        frame.addView(touch,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        frame.addOnLayoutChangeListener{_,left,top,right,bottom,_,_,_,_->
            val ratio=if(portrait)9f/16 else 16f/9
            val width=minOf(right-left,((bottom-top)*ratio).toInt())
            val height=(width/ratio).toInt()
            if(width>0 && (preview.layoutParams.width!=width || preview.layoutParams.height!=height))
                { preview.layoutParams=FrameLayout.LayoutParams(width,height,Gravity.CENTER);touch.layoutParams=FrameLayout.LayoutParams(width,height,Gravity.CENTER) }
        }
        root.addView(frame,FrameLayout.LayoutParams(-1,-1))
        page.setOnApplyWindowInsetsListener{v,insets->
            val padding=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(padding.left,padding.top,padding.right,padding.bottom);insets
        }
        val controls=LinearLayout(this).apply{orientation=1;setBackgroundColor(color("#1c1e26"));setPadding(dp(10),0,dp(10),dp(4))}
        val shade=View(this).apply{setBackgroundColor(0x55000000);visibility=View.INVISIBLE}
        root.addView(shade,FrameLayout.LayoutParams(-1,-1))
        root.addView(controls,FrameLayout.LayoutParams(dp(minOf(resources.configuration.screenWidthDp-24,300)),-1,Gravity.END))
        controls.visibility=View.INVISIBLE
        controls.isClickable=true
        fun settingsOpen(open:Boolean){
            val state=if(open)View.VISIBLE else View.INVISIBLE
            controls.visibility=state;shade.visibility=state
            if(!open)(getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(controls.windowToken,0)
        }
        shade.setOnClickListener{settingsOpen(false)}
        controls.addView(Button(this).apply{text="完成設定";setOnClickListener{settingsOpen(false)}},-1,dp(44))
        val scroll=ScrollView(this)
        panel=LinearLayout(this).apply{orientation=1}
        scroll.addView(panel);controls.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(page)
        status=label(ui("idle"));status.setTextColor(color("#e6e8ec"))
        netText=label("網路 · 檢查中");reading=label("尚未擷取")
        section("鏡頭控制")
        rotateButton=button(if(portrait)"切換為橫式" else "切換為直式"){
            requestedOrientation=if(portrait)android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        panel.addView(CheckBox(this).apply{text="自拍鏡像";setOnCheckedChangeListener{_,on->scenes.mirrorSelfie=on}})
        shortcuts=LinearLayout(this);panel.addView(shortcuts)
        lenses=spinner(ui("lens"));section("影音設定");sizes=spinner(ui("resolution"));rates=spinner(ui("fps"));bits=spinner(ui("bitrate"))
        options(bits,listOf(2,4,6,8,12));bits.setSelection(1)
        mute=Switch(this).apply{
            text=ui("mute_mic")
            setOnCheckedChangeListener{_,enabled->capture?.microphoneMuted?.set(enabled)}
        }
        panel.addView(mute)
        if(android.media.audiofx.NoiseSuppressor.isAvailable()){
            reduceNoise=CheckBox(this).apply{text="麥克風降噪"}
            panel.addView(reduceNoise)
        }
        inputs=spinner(ui("input_device"))
        val sound=getSystemService(AUDIO_SERVICE) as AudioManager
        devices=sound.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        options(inputs,listOf(ui("system_default"))+devices.map{it.productName.toString()+" · "+it.id})
        codecs=spinner(ui("codec"));options(codecs,listOf("H.264","H.265（SRT 或錄影）"))
        button("專業相機"){professional()}
        button(ui("ev")){selectExposure()}
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
        section("場景與素材")
        val transport=LinearLayout(this).apply{gravity=Gravity.CENTER}
        sceneEditor=SceneEditor(this,scenes,touch,{hide->
            val state=if(hide && capture==null && !waiting)View.GONE else View.VISIBLE
            bar.visibility=state;transport.visibility=state
        }){capture==null}
        button("選擇或新增場景"){settingsOpen(false);sceneEditor.chooseScene()}
        button("編輯素材圖層"){settingsOpen(false);sceneEditor.edit()}
        section("串流輸出")
        label("RTMP／RTMPS 或 SRT 伺服器網址")
        label("SRT 主動連線格式：srt://主機:連接埠/串流識別碼")
        address=destinationField("例如 rtmps://伺服器/live",false)
        address.background=box("#20222b","#3a3e4c",4)
        address.setPadding(dp(10),dp(6),dp(10),dp(6));address.textSize=14f
        panel.addView(address)
        label("串流金鑰")
        streamKey=destinationField("貼上金鑰；使用完整網址可留白",true)
        panel.addView(streamKey)
        button("連結 YouTube 頻道"){
            if(capture!=null || waiting || ScreenService.running) status.text="請先結束直播或錄影，再連結 YouTube 頻道。"
            else youtube.connect()
        }
        button("YouTube 預定直播與分享"){
            if(capture==null && !waiting && !ScreenService.running)youtube.connect(true)
            else status.text="請在開播前查看直播排程"
        }
        editOutputs=button("管理多路推流"){editDestinations()}
        outputSummary=label("額外目的地：0")
        saveLive=CheckBox(this).apply{text=ui("save_live")}
        panel.addView(saveLive)
        backgroundLive=CheckBox(this).apply{text="切換其他 App 時繼續相機直播"}
        panel.addView(backgroundLive)
        live=button(ui("go_live")){begin(true)}
        record=button(ui("record")){begin(false)}
        button("螢幕分享／錄影"){screenMenu()}
        val photo=button(ui("photo")){}
        photo.setOnClickListener{savePreviewPhoto(photo)}
        button(ui("diagnostics")){hardware()}
        page.addView(transport,-1,dp(46))
        fun quick(title:String,run:()->Unit){
            transport.addView(Button(this).apply{text=title;textSize=13f;minWidth=0;setPadding(0,0,0,0);setOnClickListener{run()}},LinearLayout.LayoutParams(dp(72),dp(44)))
        }
        quick("工具"){
            settingsOpen(false)
            choose("直播工具",listOf("切換鏡頭","素材圖層","選擇場景","直播設定")){index->
                when(index){
                    0->{
                        val entries=(0 until lenses.count).map{lenses.getItemAtPosition(it) as Lens}
                        if(entries.isNotEmpty())choose("選擇鏡頭",entries.map{it.toString()}){lenses.setSelection(it)}
                    }
                    1->sceneEditor.edit()
                    2->sceneEditor.chooseScene()
                    3->settingsOpen(true)
                }
            }
        }
        for(action in listOf(live,record)){
            panel.removeView(action);action.textSize=13f;action.minWidth=0;action.setPadding(0,0,0,0)
            action.background=box(if(action==live)"#d7263d" else "#3a3e4c")
            transport.addView(action,LinearLayout.LayoutParams(dp(92),dp(44)))
        }
        panel.removeView(status)
        bar.addView(status,1,LinearLayout.LayoutParams(0,-2,2f))
        status.maxLines=2;status.gravity=Gravity.CENTER
        sizes.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(v:AdapterView<*>?){}
            override fun onItemSelected(v:AdapterView<*>?,view:View?,i:Int,id:Long){if(profile==null)refreshRates()}
        }
        lenses.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(v:AdapterView<*>?){}
            override fun onItemSelected(v:AdapterView<*>?,view:View?,i:Int,id:Long){
                val next=lenses.selectedItem as? Lens?:return
                if(selected==next)return
                val running=profile
                if(running!=null && (next.sizes.none{it.width==maxOf(running.width,running.height) && it.height==minOf(running.width,running.height)} || running.fps !in next.supportedRates[android.util.Size(maxOf(running.width,running.height),minOf(running.width,running.height))].orEmpty())){
                    status.text="這顆鏡頭不支援目前規格，請停止後再更改。";lenses.setSelection((0 until lenses.count).first{lenses.getItemAtPosition(it)==selected});return
                }
                selected=next
                exposureCompensation=0;settings=CameraSettings()
                if(running==null){options(sizes,next.sizes);sizes.setSelection(next.sizes.indexOfFirst{it.width==1280}.coerceAtLeast(0))
                    refreshRates()}
                zoom.isEnabled=next.zoomMax>next.zoomMin;zoom.progress=(0..100).minByOrNull{kotlin.math.abs(zoomRatio(next,it)-1f)}?:0
                zoomLabel.text="系統變焦 1.00×"
                if(capture!=null)capture?.changeLens(next) else stopPreview{previewSelected()}
            }
        }
        preview.holder.addCallback(object:SurfaceHolder.Callback{
            override fun surfaceCreated(h:SurfaceHolder){ready=true;capture?.monitorSurface(h.surface);previewSelected()}
            override fun surfaceChanged(h:SurfaceHolder,f:Int,w:Int,t:Int){}
            override fun surfaceDestroyed(h:SurfaceHolder){ready=false;stopPreview();if(backgroundActive)capture?.monitorSurface(null) else capture?.stop()}
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
    private fun label(s:String)=TextView(this).also{it.text=s;it.setTextColor(color("#b8bdc9"));it.textSize=13f;it.setPadding(0,dp(4),0,dp(2));panel.addView(it)}
    private fun spinner(s:String)=Spinner(this).also{label(s);it.background=box("#20222b","#3a3e4c",4);it.setPadding(dp(8),0,dp(8),0);it.minimumHeight=dp(44);panel.addView(it)}
    private fun button(s:String,f:()->Unit)=Button(this).also{it.text=s;it.textSize=14f;it.setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(color("#aeb3c0"),Color.WHITE)));it.background=box("#2a2d38","#3a3e4c");panel.addView(it);it.setOnClickListener{f()}}
    private fun <T> options(view:Spinner,items:List<T>){view.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,items)}
    private fun granted()=listOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO).all{checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED}
    override fun onRequestPermissionsResult(r:Int,p:Array<String>,g:IntArray){super.onRequestPermissionsResult(r,p,g);if(r==705){status.text=if(g.firstOrNull()==PackageManager.PERMISSION_GRANTED)"已允許通知，可以開始直播" else "背景直播需要通知權限";return};if(granted())loadLenses()else status.text="請在系統設定允許使用相機與麥克風。"}
    private fun refreshRates(){
        val size=sizes.selectedItem as? android.util.Size?:return
        val available=selected?.supportedRates?.get(size).orEmpty()
        val previous=rates.selectedItem as? Int
        options(rates,available)
        if(available.isNotEmpty())rates.setSelection(available.indexOf(previous?.takeIf { it in available }?:30).coerceAtLeast(0))
    }
    private fun loadLenses(){runCatching{val list=inventory(getSystemService(CAMERA_SERVICE) as CameraManager);options(lenses,list)
        shortcuts.removeAllViews()
        val groups=list.withIndex().filter{!it.value.front && it.value.equivalent!=null}.groupBy{
            val mm=it.value.equivalent!!
            when{mm<22->ui("wide");mm<50->ui("normal");else->ui("tele")}
        }
        for(name in listOf(ui("wide"),ui("normal"),ui("tele"))){
            val group=groups[name]?:continue
            val candidate=group.sortedByDescending{it.value.physical!=null}.first()
            shortcuts.addView(Button(this).apply{text=name;background=box("#2a2d38","#3a3e4c");setTextColor(color("#e6e8ec"));setPadding(0,0,0,0);minWidth=0
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
        if(ScreenService.running){status.text="請先停止螢幕分享";return}
        if(waiting){cancelRetry();status.text="已取消重新連線";live.text=ui("go_live");return}
        if(capture!=null){ending=true;capture?.stop();status.text="正在停止";return}
        if(!ready || !granted()){status.text="請確認預覽區及相機、麥克風權限";return}
        val l=selected?:return
        val size=sizes.selectedItem as? android.util.Size?:return
        val fps=rates.selectedItem as? Int?:return
        if(fps !in l.supportedRates[size].orEmpty()){status.text="此鏡頭無法使用選取的解析度與影格率";return}
        val p=Profile(if(portrait)size.height else size.width,if(portrait)size.width else size.height,fps,(bits.selectedItem as Int)*1000000,codecs.selectedItemPosition==1)
        val uri=if(stream)runCatching{primaryDestination(address.text.toString(),streamKey.text.toString())}.getOrNull() else null
        if(stream && uri==null){status.text="請檢查伺服器網址、串流金鑰或 SRT 串流識別碼。";return}
        if(stream && p.hevc && (uri?.scheme!="srt" || additional.isNotEmpty())){status.text="H.265 直播請使用單一 SRT 目的地";return}
        if(stream && uri in additional){status.text="主要與額外目的地重複";return}
        if(stream && backgroundLive.isChecked){
            if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),705)
                status.text="啟用直播通知後，請再按開始直播"
                return
            }
            if(!getSystemService(NotificationManager::class.java).areNotificationsEnabled()){status.text="請先在系統設定開啟直播通知";return}
        }
        if(finder!=null){stopPreview{begin(stream)};return}
        outputStates.clear()
        val folder=getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val recordFile=!stream || saveLive.isChecked
        if(recordFile && folder==null){status.text="無法使用錄影資料夾";return}
        val file=if(!recordFile)null else File(folder,"Meikou-"+System.currentTimeMillis()+".mp4")
        ending=false;profile=p;status.text="正在啟動影音擷取"
        listOf(rotateButton,editOutputs,saveLive,inputs,sizes,rates,bits,codecs,address,streamKey).forEach{it.isEnabled=false}
        live.isEnabled=stream;record.isEnabled=!stream
        if(stream)live.text=ui("stop_live") else record.text=ui("stop_record")
        val session=Capture(this,preview.holder.surface,p,scenes,l,devices.getOrNull(inputs.selectedItemPosition-1),zoomRatio(l,zoom.progress),exposureCompensation,settings,{s->runOnUiThread{reading.text=s}},uri,if(stream)additional else emptyList(),{index,value->runOnUiThread{
            outputStates[index]=value;outputSummary.text=outputStates.entries.joinToString("  "){"目的地 "+it.key+"："+it.value}
        }},file,
            {mode->runOnUiThread{if(!ending){if(backgroundActive && mode==1)CameraLiveService.update("相機直播中，可點選停止直播");monitorState(mode==1);status.text=when{mode!=1->"錄影中";file!=null->"直播中 · 同步錄影中";else->"直播中 · 已送出影音"}}}},
            {ok,networkError,recorded->
                val saved=if(recorded && file!=null)saveMovie(file) else null
                runOnUiThread{if(backgroundActive){CameraLiveService.finish();backgroundActive=false};backgroundLive.isEnabled=true;reduceNoise?.isEnabled=true;capture=null;profile=null;monitorState(false)
                listOf(rotateButton,editOutputs,saveLive,inputs,sizes,rates,bits,codecs,address,streamKey,live,record).forEach{it.isEnabled=true}
                live.text=ui("go_live");record.text=ui("record");reading.text="已停止擷取"
                status.text=when{
                    saved!=null && !ok->"傳輸中斷，已儲存 "+saved
                    saved!=null->"已儲存 "+saved
                    file!=null || !ok->"擷取、傳輸或錄影未完成，請檢查設備與連線。"
                    else->"直播已結束"
                }
                if(stream && networkError && !ending && visible && attempts<3){
                    attempts++;waiting=true;val delay=1000L shl attempts
                    status.text="連線中斷，"+(delay/1000)+" 秒後重試（"+attempts+"/3）"
                    live.text="取消重新連線"
                    handlers.postDelayed({waiting=false;if(visible && capture==null)begin(true)},delay)
                }else{attempts=0;previewSelected()}
            }},unsupportedLens={unavailable->runOnUiThread{
            if(selected==unavailable && capture!=null){
                val main=(0 until lenses.count).firstOrNull { index ->
                    val candidate=lenses.getItemAtPosition(index) as Lens
                    !candidate.front && candidate.physical==null && p.fps in candidate.supportedRates[size].orEmpty()
                }
                if(main!=null){status.text="無法確認實體鏡頭，已要求切回主鏡頭";lenses.setSelection(main)}
                else{ending=true;capture?.stop();status.text="無可用的主鏡頭組合，已停止擷取"}
            }
        }})
        capture=session
        reduceNoise?.isEnabled=false
        session.reduceNoise=reduceNoise?.isChecked==true
        session.microphoneMuted.set(mute.isChecked)
        backgroundLive.isEnabled=false
        backgroundActive=stream && backgroundLive.isChecked
        if(!backgroundActive){session.start();return}
        backgroundPreparing=true
        fun cancel(){
            ending=true;session.stop()
            if(backgroundPreparing){backgroundPreparing=false;CameraLiveService.pendingStart=null;session.start()}
        }
        CameraLiveService.pendingStart={
            backgroundPreparing=false
            if(!visible || ending)session.stop()
            session.start()
        }
        CameraLiveService.stopSession={cancel()}
        CameraLiveService.startFailed={cancel();status.text="無法啟用背景直播，請檢查通知與相機權限"}
        try{startForegroundService(Intent(this,CameraLiveService::class.java))}
        catch(e:RuntimeException){CameraLiveService.startFailed?.invoke()}
    }
    private var pendingScreen:Intent?=null
    private fun screenMenu(){
        if(ScreenService.running){startService(Intent(this,ScreenService::class.java).setAction(ScreenService.STOP));return}
        if(capture!=null || waiting || !granted()){status.text="請先停止相機直播或錄影，並允許麥克風權限";return}
        choose("螢幕分享",listOf("直播螢幕與麥克風","錄製螢幕與麥克風","直播螢幕、麥克風與內部音訊","錄製螢幕、麥克風與內部音訊")){choice->
            val stream=choice%2==0
            val url=if(stream)runCatching{primaryDestination(address.text.toString(),streamKey.text.toString())}.getOrNull() else null
            if(stream && (url==null || url in additional)){status.text="請先設定有效且不重複的推流網址";return@choose}
            val size=sizes.selectedItem as? android.util.Size ?:android.util.Size(1280,720)
            pendingScreen=Intent(this,ScreenService::class.java).apply{
                putExtra("playback",choice>=2)
                putExtra("w",if(portrait)size.height else size.width);putExtra("h",if(portrait)size.width else size.height)
                putExtra("fps",rates.selectedItem as? Int ?:30);putExtra("bitrate",((bits.selectedItem as? Int)?:4)*1000000)
                putExtra("target",url?.toString());putExtra("record",saveLive.isChecked);putExtra("muted",mute.isChecked)
                putExtra("input",devices.getOrNull(inputs.selectedItemPosition-1)?.id?:-1)
                putStringArrayListExtra("additional",ArrayList(if(stream)additional.map{it.toString()} else emptyList()))
            }
            val manager=getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(),703)
        }
    }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==YouTubeChannel.REQUEST){youtube.returned(resultCode,data);return}
        if(requestCode==703){
            val session=pendingScreen;pendingScreen=null
            if(resultCode==RESULT_OK && data!=null && session!=null){
                stopPreview();session.putExtra("consent",data)
                startForegroundService(session)
                status.text="已要求開始螢幕擷取，可由系統通知停止"
            }
            return
        }
        if(requestCode in listOf(SceneEditor.PICK,SceneEditor.PICK_VIDEO) && resultCode==RESULT_OK)data?.data?.let{sceneEditor.media(it,requestCode==SceneEditor.PICK_VIDEO)}
    }
    private fun savePreviewPhoto(button:Button){
        val holder=preview.holder
        if(!ready || !holder.surface.isValid){status.text=ui("photo_unavailable");return}
        val size=holder.surfaceFrame
        if(size.width()<=0 || size.height()<=0){status.text=ui("photo_unavailable");return}
        button.isEnabled=false
        val bitmap=android.graphics.Bitmap.createBitmap(size.width(),size.height(),android.graphics.Bitmap.Config.ARGB_8888)
        try{
            PixelCopy.request(preview,bitmap,{result->
                if(result!=PixelCopy.SUCCESS){
                    bitmap.recycle();button.isEnabled=true
                    Toast.makeText(this,ui("photo_unavailable"),Toast.LENGTH_SHORT).show()
                }else kotlin.concurrent.thread(name="Save photo"){
                    var uri:android.net.Uri?=null
                    val ok=runCatching{
                        val values=ContentValues().apply{
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,"Meikou-"+System.currentTimeMillis()+".jpg")
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"image/jpeg")
                            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Pictures/Meikou Live")
                            put(android.provider.MediaStore.MediaColumns.IS_PENDING,1)
                        }
                        val dest=requireNotNull(contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values))
                        uri=dest
                        requireNotNull(contentResolver.openOutputStream(dest)).use{check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,it))}
                        values.clear();values.put(android.provider.MediaStore.MediaColumns.IS_PENDING,0)
                        check(contentResolver.update(dest,values,null,null)==1)
                    }.isSuccess
                    if(!ok)uri?.let{runCatching{contentResolver.delete(it,null,null)}}
                    bitmap.recycle()
                    runOnUiThread{button.isEnabled=true;Toast.makeText(this,ui(if(ok)"photo_saved" else "photo_unavailable"),Toast.LENGTH_SHORT).show()}
                }
            },handlersForPhotos)
        }catch(e:Exception){bitmap.recycle();button.isEnabled=true;Toast.makeText(this,ui("photo_unavailable"),Toast.LENGTH_SHORT).show()}
    }
    private val handlersForPhotos=Handler(Looper.getMainLooper())
    override fun onStop(){super.onStop();visible=false;stopPreview();cancelRetry();if(!backgroundActive){ending=true;capture?.stop()}else if(backgroundPreparing)CameraLiveService.stopSession?.invoke()}
    override fun onDestroy(){if(backgroundActive)CameraLiveService.stopSession?.invoke();network.unregisterNetworkCallback(monitor);super.onDestroy()}
}

