package tw.meikou.live

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.*
import java.io.File
import java.net.URI

/** Explicit user consent and a visible, stoppable foreground session for screen sharing. */
class ScreenService:Service(){
    companion object{
        @Volatile var running=false
            private set
        const val CHANNEL="screen_broadcast"
        const val STOP="tw.meikou.live.STOP_SCREEN"
    }
    private var current:Capture?=null
    private val main=Handler(Looper.getMainLooper())
    private val notices by lazy{getSystemService(NOTIFICATION_SERVICE) as NotificationManager}
    private fun notice(text:String):Notification{
        val stop=PendingIntent.getService(this,0,Intent(this,ScreenService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open=PendingIntent.getActivity(this,1,Intent(this,Studio::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Meikou Live · 螢幕分享").setContentText(text).setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null,"停止分享",stop).build()).build()
    }
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action==STOP){current?.stop();if(current==null)stopSelf();return START_NOT_STICKY}
        if(running || intent==null)return START_NOT_STICKY
        try{
            notices.createNotificationChannel(NotificationChannel(CHANNEL,"螢幕直播與錄影",NotificationManager.IMPORTANCE_LOW))
            val notification=notice("正在啟動，隨時可停止分享")
            startForeground(11,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            val consent=requireNotNull(intent.getParcelableExtra<Intent>("consent"))
            val projection=requireNotNull((getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(Activity.RESULT_OK,consent))
            val profile=Profile(intent.getIntExtra("w",1280),intent.getIntExtra("h",720),intent.getIntExtra("fps",30),intent.getIntExtra("bitrate",4000000),false)
            val target=intent.getStringExtra("target")?.let(::URI)
            val file=if(target==null || intent.getBooleanExtra("record",false))File(requireNotNull(getExternalFilesDir(Environment.DIRECTORY_MOVIES)),"Meikou-screen-"+System.currentTimeMillis()+".mp4") else null
            val urls=intent.getStringArrayListExtra("additional").orEmpty().map(::URI)
            val input=(getSystemService(AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull{it.id==intent.getIntExtra("input",-1)}
            running=true
            current=Capture(this,null,profile,Scenes(this),null,input,1f,0,CameraSettings(),{},target,urls,{_,_->},file,
                {mode->main.post{notices.notify(11,notice(if(mode==1)"正在傳送螢幕與麥克風" else "正在錄製螢幕與麥克風"))}},
                {ok,_,recorded->
                    val saved=if(recorded && file!=null)saveMovie(file) else null
                    main.post{
                        android.widget.Toast.makeText(this,saved?.let{"已儲存 "+it}?:if(ok)"已結束螢幕分享" else "螢幕擷取或網路傳輸未完成",android.widget.Toast.LENGTH_LONG).show()
                        current=null;running=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
                    }
                },projection).also{it.mixPlayback=intent.getBooleanExtra("playback",false);it.microphoneMuted.set(intent.getBooleanExtra("muted",false));it.start()}
        }catch(e:Exception){running=false;current?.stop();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();android.widget.Toast.makeText(this,"無法啟動螢幕分享，請確認系統授權",android.widget.Toast.LENGTH_LONG).show()}
        return START_NOT_STICKY
    }
    override fun onDestroy(){current?.stop();running=false;super.onDestroy()}
}
