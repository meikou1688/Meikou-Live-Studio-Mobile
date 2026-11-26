package tw.meikou.live

import android.app.Activity
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
internal class YouTubeChannel(private val activity: Activity, private val show: (String) -> Unit) {
    companion object { const val REQUEST = 704 }
    private val client by lazy { Identity.getAuthorizationClient(activity) }
    private var busy = false

    private var schedule=false
    fun connect(upcoming:Boolean=false) {
        if (busy) return
        busy = true
        schedule=upcoming
        show("正在向 Google 要求讀取 YouTube 頻道…")
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/youtube.readonly")))
            .build()
        client.authorize(request).addOnSuccessListener(activity) { result ->
            val consent = result.pendingIntent
            if (result.hasResolution() && consent != null) {
                try {
                    activity.startIntentSenderForResult(consent.intentSender, REQUEST, null, 0, 0, 0)
                } catch (_: android.content.IntentSender.SendIntentException) {
                    finish("無法開啟 Google 授權視窗，請重試。")
                }
            } else read(result)
        }.addOnFailureListener(activity) { error ->
            finish("Google 授權失敗（代碼 ${(error as? ApiException)?.statusCode ?: "未知"}）。請確認 Google Play 服務與網路連線。")
        }
    }

    fun returned(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            finish("已取消 YouTube 頻道授權。")
            return
        }
        try {
            read(client.getAuthorizationResultFromIntent(data))
        } catch (error: ApiException) {
            finish("Google 授權失敗（代碼 ${error.statusCode}）。")
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
        busy = false
        if (!activity.isDestroyed && !activity.isFinishing) show(message)
    }
}
