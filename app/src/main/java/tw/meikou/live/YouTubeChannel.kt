package tw.meikou.live

import android.app.AlertDialog
import android.app.Activity
import android.app.PendingIntent
import android.content.pm.PackageManager
import java.security.MessageDigest
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Reads the channel the user authorized. Tokens live only for the request. */
internal class YouTubeChannel(private val activity: Activity, private val applyIngest:(String,String)->Unit, private val manualStream: ()->Unit, private val show: (String) -> Unit) {
    companion object { const val REQUEST = 704 }
    private val client by lazy { Identity.getAuthorizationClient(activity) }
    private var busy = false
    private var pendingConsent: PendingIntent? = null
    private var resumed = false
    private var pendingError: (() -> Unit)? = null

    fun setResumed(value: Boolean) {
        resumed = value
        if (!value) return
        pendingError?.let { pendingError = null; it() }
        val consent = pendingConsent ?: return
        pendingConsent = null
        try {
            activity.startIntentSenderForResult(consent.intentSender, REQUEST, null, 0, 0, 0)
        } catch (_: android.content.IntentSender.SendIntentException) {
            finish("無法開啟 Google 授權視窗，請重試。")
        }
    }

    private fun authorizationError(code: Int?, missingResult: Boolean = false) {
        val message = if (missingResult || code == 16 || code == 12501) activity.getString(R.string.youtube_no_result) else when (code) {
            10 -> activity.getString(R.string.youtube_oauth_configuration)
            7 -> activity.getString(R.string.youtube_oauth_network)
            else -> activity.getString(R.string.youtube_oauth_failed)
        }
        finish(message)
        if (activity.isFinishing || activity.isDestroyed) return
        val display = {
            val detail = if (code == 10) {
                val info = activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val fingerprints = info.signingInfo?.apkContentsSigners.orEmpty().joinToString("\n") { cert ->
                    MessageDigest.getInstance("SHA-1").digest(cert.toByteArray()).joinToString(":") { "%02X".format(it) }
                }
                "\n\n${activity.packageName}\nSHA-1  $fingerprints"
            } else ""
            AlertDialog.Builder(activity).setTitle("YouTube")
                .setMessage(message + (code?.let { "\nGoogle · $it" } ?: "") + detail)
                .setNeutralButton(R.string.youtube_manual) { _, _ -> manualStream() }
                .setPositiveButton(android.R.string.ok, null).show()
            Unit
        }
        if (resumed) display() else pendingError = display
    }

    private data class Draft(val title:String,val start:String,val privacy:String,val latency:String)
    private var draft:Draft?=null
    private var chat=false
    private var schedule=false
    private var chooseStream=false
    fun plan(){
        if(busy)return
        val title=android.widget.EditText(activity).apply{hint="直播標題";setSingleLine();filters=arrayOf(android.text.InputFilter.LengthFilter(100))}
        val privacy=android.widget.Spinner(activity).apply{adapter=android.widget.ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,listOf("私人","不公開","公開"))}
        val latency=android.widget.Spinner(activity).apply{adapter=android.widget.ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,listOf("一般延遲","低延遲","超低延遲"))}
        val form=android.widget.LinearLayout(activity).apply{orientation=1;addView(title);addView(privacy);addView(latency)}
        android.app.AlertDialog.Builder(activity).setTitle("建立 YouTube 預定直播").setView(form).setNegativeButton("取消",null)
            .setPositiveButton("選擇日期"){_,_->
                val name=title.text.toString().trim()
                if(name.isEmpty()){show("請填寫直播標題");return@setPositiveButton}
                val now=java.time.ZonedDateTime.now()
                android.app.DatePickerDialog(activity,{_,year,month,day->
                    android.app.TimePickerDialog(activity,{_,hour,minute->
                        val at=java.time.LocalDateTime.of(year,month+1,day,hour,minute).atZone(now.zone)
                        if(!at.isAfter(java.time.ZonedDateTime.now())){show("請選擇未來的直播時間");return@TimePickerDialog}
                        val draftValue=Draft(name,at.toInstant().toString(),listOf("private","unlisted","public")[privacy.selectedItemPosition],listOf("normal","low","ultraLow")[latency.selectedItemPosition])
                        android.app.AlertDialog.Builder(activity).setTitle("建立預定直播？").setMessage(name+"\n"+at.toLocalDateTime()+" · "+privacy.selectedItem)
                            .setNegativeButton("取消",null).setPositiveButton("建立"){_,_->draft=draftValue;connect()}.show()
                    },now.hour,now.minute,true).show()
                },now.year,now.monthValue-1,now.dayOfMonth).show()
            }.show()
    }
    fun connect(upcoming:Boolean=false,liveChat:Boolean=false,existingStream:Boolean=false) {
        if (busy) return
        busy = true
        chooseStream=existingStream
        schedule=upcoming
        chat=liveChat
        show("正在向 Google 要求讀取 YouTube 頻道…")
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(if(draft!=null)"https://www.googleapis.com/auth/youtube.force-ssl" else "https://www.googleapis.com/auth/youtube.readonly")))
            .build()
        client.authorize(request).addOnSuccessListener { result ->
            if (activity.isFinishing || activity.isDestroyed) return@addOnSuccessListener
            val consent = result.pendingIntent
            if (result.hasResolution() && consent != null) {
                pendingConsent = consent
                setResumed(resumed)
            } else read(result)
        }.addOnFailureListener { error ->
            authorizationError((error as? ApiException)?.statusCode)
        }
    }

    fun returned(resultCode: Int, data: Intent?) {
        if (data == null) {
            authorizationError(null, missingResult = true)
            return
        }
        try {
            val result = client.getAuthorizationResultFromIntent(data)
            if (resultCode == Activity.RESULT_OK) read(result)
            else authorizationError(null, missingResult = true)
        } catch (error: ApiException) {
            authorizationError(error.statusCode)
        }

    }

    private fun read(result: AuthorizationResult) {
        val token = result.accessToken
        if (token.isNullOrBlank()) {
            finish("Google 未核發讀取頻道的授權。")
            return
        }
        Thread {
            val message = runCatching {
                draft?.let{return@runCatching create(token,it)}
                if(chooseStream)return@runCatching existingStreams(token)
                if(chat)return@runCatching openChat(token)
                if(schedule)return@runCatching upcoming(token)
                val url = URL("https://www.googleapis.com/youtube/v3/channels?part=snippet&mine=true")
                val connection = url.openConnection() as HttpsURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    connection.setRequestProperty("Authorization", "Bearer " + token)
                    val code = connection.responseCode
                    if (code != 200) "YouTube 無法讀取頻道（HTTP ${code}）。" else {
                        val payload = connection.inputStream.bufferedReader().use { it.readText() }
                        val items = JSONObject(payload).getJSONArray("items")
                        if (items.length() == 0) "此 Google 帳號沒有可讀取的 YouTube 頻道。"
                        else (0 until items.length()).joinToString("、", prefix = "已連結頻道：") {
                            items.getJSONObject(it).getJSONObject("snippet").getString("title")
                        }
                    }
                } finally { connection.disconnect() }
            }.getOrDefault("無法取得 YouTube 頻道資料，請確認網路後重試。")
            activity.runOnUiThread { finish(message) }
        }.start()
    }

    private fun api(token:String,path:String,body:JSONObject?=null,post:Boolean=false):JSONObject{
        val c=URL("https://www.googleapis.com/youtube/v3/"+path).openConnection() as HttpsURLConnection
        try{
            c.instanceFollowRedirects=false;c.connectTimeout=10000;c.readTimeout=15000
            c.setRequestProperty("Authorization","Bearer "+token)
            if(body!=null || post){
                c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json; charset=utf-8")
                c.outputStream.use{if(body!=null)it.write(body.toString().toByteArray(Charsets.UTF_8))}
            }
            if(c.responseCode !in 200..299)throw java.io.IOException("YouTube HTTP "+c.responseCode)
            return JSONObject(c.inputStream.bufferedReader().use{it.readText()})
        }finally{c.disconnect()}
    }
    private fun existingStreams(token: String): String {
        data class Ingest(val title: String, val address: String, val key: String)
        val streams = mutableListOf<Ingest>()
        var page = ""
        do {
            val response = api(token, "liveStreams?part=snippet,cdn&mine=true&maxResults=50" + page)
            val items = response.getJSONArray("items")
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val info = item.optJSONObject("cdn")?.optJSONObject("ingestionInfo") ?: continue
                val address = info.optString("rtmpsIngestionAddress")
                val key = info.optString("streamName")
                if (key.isBlank() || runCatching { streamDestination(address,key).scheme }.getOrNull() != "rtmps") continue
                streams += Ingest(item.getJSONObject("snippet").getString("title"), address, key)
            }
            page = response.optString("nextPageToken").let { if (it.isBlank()) "" else "&pageToken=" + android.net.Uri.encode(it) }
        } while (page.isNotEmpty())
        if (streams.isEmpty()) return activity.getString(R.string.youtube_no_streams)
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) {
                AlertDialog.Builder(activity).setTitle(R.string.youtube_existing)
                    .setItems(streams.map { it.title }.toTypedArray()) { _, index ->
                        val stream = streams[index]
                        applyIngest(stream.address, stream.key)
                        show(activity.getString(R.string.youtube_ready))
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }
        }
        return activity.getString(R.string.youtube_choose_stream)
    }

    private fun create(token:String,request:Draft):String{
        val event=JSONObject().put("snippet",JSONObject().put("title",request.title).put("scheduledStartTime",request.start))
            .put("status",JSONObject().put("privacyStatus",request.privacy))
            .put("contentDetails",JSONObject().put("latencyPreference",request.latency).put("enableAutoStart",false).put("enableAutoStop",false))
        val broadcast=try{api(token,"liveBroadcasts?part=id,snippet,contentDetails,status",event).getString("id")}
            catch(e:Exception){return "未能確認排程結果，請先查看 YouTube 預定直播清單再重試"}
        try{
            val input=JSONObject().put("snippet",JSONObject().put("title",request.title))
                .put("cdn",JSONObject().put("ingestionType","rtmp").put("resolution","variable").put("frameRate","variable"))
                .put("contentDetails",JSONObject().put("isReusable",false))
            val stream=api(token,"liveStreams?part=id,cdn,snippet,contentDetails",input)
            val ingest=stream.getJSONObject("cdn").getJSONObject("ingestionInfo")
            val address=ingest.getString("rtmpsIngestionAddress")
            val key=ingest.getString("streamName")
            require(streamDestination(address,key).scheme=="rtmps")
            api(token,"liveBroadcasts/bind?part=id,contentDetails&id="+android.net.Uri.encode(broadcast)+"&streamId="+android.net.Uri.encode(stream.getString("id")),post=true)
            activity.runOnUiThread{if(!activity.isFinishing && !activity.isDestroyed)applyIngest(address,key)}
            return "已建立排程並套用金鑰。手機開始傳送後，請在 YouTube 控制中心確認開播。"
        }catch(e:Exception){return "排程已建立，串流設定未完成。請在 YouTube 控制中心檢查這場直播。"}
    }

    private fun openChat(token:String):String{
        val rooms=mutableListOf<Pair<String,String>>()
        var next=""
        do{
            val response=api(token,"liveBroadcasts?part=snippet&broadcastStatus=active&broadcastType=all&maxResults=50"+next)
            val items=response.getJSONArray("items")
            for(index in 0 until items.length()){
                val snippet=items.getJSONObject(index).getJSONObject("snippet")
                val room=snippet.optString("liveChatId")
                if(room.isNotBlank())rooms.add(snippet.getString("title") to room)
            }
            next=response.optString("nextPageToken").let{if(it.isBlank())"" else "&pageToken="+android.net.Uri.encode(it)}
        }while(next.isNotEmpty())
        if(rooms.isEmpty())return "此頻道目前沒有已開放的直播聊天室"
        activity.runOnUiThread{
            if(!activity.isFinishing && !activity.isDestroyed)android.app.AlertDialog.Builder(activity)
                .setTitle("選擇直播聊天室").setItems(rooms.map{it.first}.toTypedArray()){_,index->
                    chatDialog(token,rooms[index].first,rooms[index].second)
                }.setNegativeButton("關閉",null).show()
        }
        return "已讀取直播聊天室"
    }
    private fun chatDialog(token:String,title:String,room:String){
        val view=android.widget.TextView(activity).apply{setPadding(18,12,18,12);text="正在讀取留言"}
        val scroll=android.widget.ScrollView(activity).apply{addView(view)}
        val dialog=android.app.AlertDialog.Builder(activity).setTitle(title).setView(scroll).setNegativeButton("關閉",null).create()
        val stopped=java.util.concurrent.atomic.AtomicBoolean()
        val thread=Thread{
            val messages=java.util.LinkedHashMap<String,String>()
            var page=""
            try{
                while(!stopped.get() && !activity.isDestroyed && !activity.isFinishing){
                    val response=api(token,"liveChat/messages?part=snippet,authorDetails&maxResults=200&liveChatId="+android.net.Uri.encode(room)+page)
                    val list=response.getJSONArray("items")
                    for(index in 0 until list.length()){
                        val item=list.getJSONObject(index)
                        val body=item.getJSONObject("snippet")
                        val id=item.getString("id")
                        val deleted=body.optJSONObject("messageDeletedDetails")?.optString("deletedMessageId")
                        if(!deleted.isNullOrBlank()){messages.remove(deleted);continue}
                        val message=body.optString("displayMessage")
                        if(message.isNotBlank())messages[id]=item.optJSONObject("authorDetails")?.optString("displayName").orEmpty()+"　"+message
                    }
                    while(messages.size>100){val iter=messages.keys.iterator();iter.next();iter.remove()}
                    val contents=messages.values.joinToString("\n").ifEmpty{"等待觀眾留言"}
                    activity.runOnUiThread{if(dialog.isShowing){view.text=contents;scroll.post{scroll.fullScroll(android.view.View.FOCUS_DOWN)}}}
                    val cursor=response.optString("nextPageToken")
                    if(cursor.isEmpty())break
                    page="&pageToken="+android.net.Uri.encode(cursor)
                    Thread.sleep(response.optLong("pollingIntervalMillis",5000).coerceAtLeast(1000))
                }
            }catch(_:InterruptedException){}catch(_:Exception){
                activity.runOnUiThread{if(dialog.isShowing)view.append("\n聊天室已中斷，請關閉後重新連線")}
            }
        }
        dialog.setOnDismissListener{stopped.set(true);thread.interrupt()}
        dialog.show();thread.start()
    }

    private fun upcoming(token:String):String {
        val events=mutableListOf<Pair<String,String>>()
        var page=""
        do {
            val params="part=id,snippet,status&broadcastStatus=upcoming&broadcastType=all&maxResults=50"
            val next=if(page.isEmpty()) "" else "&pageToken="+android.net.Uri.encode(page)
            val connection=URL("https://www.googleapis.com/youtube/v3/liveBroadcasts?"+params+next).openConnection() as HttpsURLConnection
            try{
                connection.instanceFollowRedirects=false
                connection.connectTimeout=10000;connection.readTimeout=10000
                connection.setRequestProperty("Authorization","Bearer "+token)
                if(connection.responseCode!=200)return "無法讀取 YouTube 直播排程（HTTP "+connection.responseCode+"）"
                val data=JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val items=data.getJSONArray("items")
                for(i in 0 until items.length()){
                    val item=items.getJSONObject(i)
                    val snippet=item.getJSONObject("snippet")
                    val time=runCatching {
                        val instant=java.time.Instant.parse(snippet.getString("scheduledStartTime"))
                        java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(java.time.ZoneId.systemDefault()).format(instant)
                    }.getOrDefault("")
                    events.add((time+"  "+snippet.getString("title")) to item.getString("id"))
                }
                page=data.optString("nextPageToken")
            }finally{connection.disconnect()}
        }while(page.isNotEmpty())
        if(events.isEmpty())return "此頻道目前沒有預定直播"
        activity.runOnUiThread {
            if(!activity.isFinishing && !activity.isDestroyed)android.app.AlertDialog.Builder(activity)
                .setTitle("選擇要分享的預定直播").setItems(events.map{it.first}.toTypedArray()){_,index->
                    val link="https://www.youtube.com/watch?v="+android.net.Uri.encode(events[index].second)
                    val share=Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,link)
                    activity.startActivity(Intent.createChooser(share,"分享直播連結"))
                }.setNegativeButton("關閉",null).show()
        }
        return "已讀取 "+events.size+" 場預定直播"
    }

    private fun finish(message: String) {
        draft=null
        busy = false
        if (!activity.isDestroyed && !activity.isFinishing) show(message)
    }
}
