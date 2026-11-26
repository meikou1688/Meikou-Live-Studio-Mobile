package tw.meikou.live

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** The camera is opened only after Android grants foreground execution. All session transitions run on the main thread. */
class CameraLiveService : Service() {
    companion object {
        private const val CHANNEL="camera_live"
        private const val ID=12
        private const val STOP="tw.meikou.live.STOP_CAMERA"
        var pendingStart:(()->Unit)?=null
        var stopSession:(()->Unit)?=null
        var startFailed:(()->Unit)?=null
        private var instance:CameraLiveService?=null
        fun update(text:String){instance?.show(text)}
        fun finish(){
            pendingStart=null
            stopSession=null
            startFailed=null
            instance?.stopSelf()
        }
    }
    private val manager by lazy { getSystemService(NotificationManager::class.java) }
    private fun notification(text:String):Notification {
        val stop=PendingIntent.getService(this,12,Intent(this,CameraLiveService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE)
        val open=PendingIntent.getActivity(this,13,Intent(this,Studio::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Meikou Live Studio").setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null,"停止直播",stop).build()).build()
    }
    private fun show(text:String){manager.notify(ID,notification(text))}
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action==STOP){stopSession?.invoke();return START_NOT_STICKY}
        try{
            manager.createNotificationChannel(NotificationChannel(CHANNEL,"相機背景直播",NotificationManager.IMPORTANCE_LOW))
            check(manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL).importance!=NotificationManager.IMPORTANCE_NONE)
            startForeground(ID,notification("正在準備相機與麥克風"),ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            instance=this
            val start=pendingStart
            pendingStart=null
            if(start!=null)start() else if(stopSession==null)stopSelf()
        }catch(e:RuntimeException){startFailed?.invoke();finish();stopSelf()}
        return START_NOT_STICKY
    }
    override fun onDestroy(){
        if(instance===this){instance=null;stopSession?.invoke()}
        super.onDestroy()
    }
}
