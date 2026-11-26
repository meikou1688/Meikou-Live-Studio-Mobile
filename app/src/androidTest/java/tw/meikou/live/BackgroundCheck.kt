package tw.meikou.live

import android.app.*
import android.content.Intent
import android.os.*
import android.widget.*
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask

/** Exercises the production Activity, Camera2, AudioRecord, encoder, notification and RTMP publisher. */
class BackgroundCheck : Instrumentation() {
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    private fun <T> ui(block:()->T):T{
        val task=FutureTask(Callable(block))
        runOnMainSync(task)
        return task.get()
    }
    private fun field(activity:Activity,name:String):Any? =
        activity.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(activity)
    private fun until(check:()->Boolean){
        val deadline=SystemClock.elapsedRealtime()+30000
        while(!check()){
            require(SystemClock.elapsedRealtime()<deadline){"等待狀態逾時"}
            SystemClock.sleep(150)
        }
    }
    override fun onStart(){
        val result=Bundle()
        try{
            val launch=Intent(targetContext,Studio::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val activity=startActivitySync(launch)
            ui { (field(activity,"address") as EditText).setText("rtmp://127.0.0.1:1935/live")
                (field(activity,"streamKey") as EditText).setText("integration")
                (field(activity,"backgroundLive") as CheckBox).isChecked=true }
            until { ui { (field(activity,"rates") as Spinner).count>0 && field(activity,"finder")!=null } }
            ui { (field(activity,"live") as Button).performClick() }
            until { ui { (field(activity,"status") as TextView).text.startsWith("直播中") } }
            SystemClock.sleep(2500)
            uiAutomation.performGlobalActionCompatHome()
            until { ui { field(activity,"visible")==false } }
            SystemClock.sleep(10000)
            targetContext.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            until { ui { field(activity,"visible")==true && (field(activity,"ready")==true) } }
            SystemClock.sleep(2500)
            val manager=targetContext.getSystemService(NotificationManager::class.java)
            manager.activeNotifications.first { it.id==12 }.notification.actions.first().actionIntent.send()
            until { ui { field(activity,"capture")==null } && manager.activeNotifications.none { it.id==12 } }
            result.putString("stream","MEIKOU_BACKGROUND_OK")
            finish(Activity.RESULT_OK,result)
        }catch(error:Throwable){
            result.putString("stream","FAILED: "+error.stackTraceToString())
            finish(Activity.RESULT_CANCELED,result)
        }
    }
    private fun UiAutomation.performGlobalActionCompatHome(){
        executeShellCommand("input keyevent KEYCODE_HOME").use {
            ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
    }
}
