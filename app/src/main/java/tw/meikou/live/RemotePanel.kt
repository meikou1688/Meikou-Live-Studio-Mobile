package tw.meikou.live

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.widget.EditText
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.*
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.*
import kotlin.concurrent.thread

internal class PeerServer private constructor(private val socket:SSLServerSocket,val invitation:String,
        private val secret:String,private val command:(String,JSONObject)->JSONObject):Closeable{
    private val closed=AtomicBoolean()
    @Volatile private var peer:Socket?=null
    private fun listen(){
        thread(name="Meikou remote",isDaemon=true){
            while(!closed.get()){
                try{
                    (socket.accept() as SSLSocket).use{client->
                        peer=client
                        if(closed.get())return@use
                        client.soTimeout=3500
                        client.startHandshake()
                        val req=JSONObject(DataInputStream(client.inputStream).readUTF())
                        val valid=MessageDigest.isEqual(secret.toByteArray(),req.optString("token").toByteArray())
                        require(valid && req.getInt("protocolVersion")==1)
                        val id=UUID.fromString(req.getString("requestId")).toString()
                        val result=command(req.getString("command"),req.optJSONObject("parameters")?:JSONObject())
                        val reply=JSONObject().put("protocolVersion",1).put("requestId",id).put("result",result)
                        DataOutputStream(client.outputStream).apply{writeUTF(reply.toString());flush()}
                    }
                }catch(_:Exception){}finally{peer=null}
            }
        }
    }
    override fun close(){closed.set(true);runCatching{socket.close()};runCatching{peer?.close()}}
    companion object{
        private const val ALIAS="meikou-remote-v1"
        private fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it.toInt() and 255)}
        private fun fingerprint(cert:X509Certificate)=hex(MessageDigest.getInstance("SHA-256").digest(cert.encoded))
        @Synchronized fun start(ip:Inet4Address,command:(String,JSONObject)->JSONObject):PeerServer{
            val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
            if(!store.containsAlias(ALIAS)){
                val generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore")
                generator.initialize(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA384,KeyProperties.DIGEST_SHA512).build())
                generator.generateKeyPair()
            }
            val cert=store.getCertificate(ALIAS) as X509Certificate
            val manager=object:X509KeyManager{
                override fun getClientAliases(t:String?,i:Array<out Principal>?):Array<String>?=null
                override fun chooseClientAlias(t:Array<out String>?,i:Array<out Principal>?,s:Socket?):String?=null
                override fun getServerAliases(t:String?,i:Array<out Principal>?)=if(t?.startsWith("EC")==true)arrayOf(ALIAS) else null
                override fun chooseServerAlias(t:String?,i:Array<out Principal>?,s:Socket?)=getServerAliases(t,i)?.firstOrNull()
                override fun getCertificateChain(alias:String?)=if(alias==ALIAS)arrayOf(cert) else null
                override fun getPrivateKey(alias:String?)=if(alias==ALIAS)store.getKey(ALIAS,null) as PrivateKey else null
            }
            val context=SSLContext.getInstance("TLS").apply{init(arrayOf(manager),null,SecureRandom())}
            val socket=context.serverSocketFactory.createServerSocket() as SSLServerSocket
            try{
                socket.enabledProtocols=socket.supportedProtocols.filter{it=="TLSv1.2" || it=="TLSv1.3"}.toTypedArray()
                socket.bind(InetSocketAddress(ip,0),4)
                val token=hex(ByteArray(32).also{SecureRandom().nextBytes(it)})
                val invite="meikou-live://"+ip.hostAddress+":"+socket.localPort+"/"+fingerprint(cert)+"/"+token
                return PeerServer(socket,invite,token,command).also{it.listen()}
            }catch(e:Exception){socket.close();throw e}
        }
        fun send(invitation:String,action:String,parameters:JSONObject=JSONObject()):JSONObject{
            val uri=URI(invitation.trim())
            require(uri.scheme=="meikou-live" && uri.port in 1..65535 && uri.userInfo==null &&
                uri.query==null && uri.fragment==null && android.net.InetAddresses.isNumericAddress(uri.host))
            val fields=uri.path.trim('/').split('/')
            require(fields.size==2 && fields.all{it.matches(Regex("[0-9a-f]{64}"))})
            val trust=object:X509TrustManager{
                override fun getAcceptedIssuers()=emptyArray<X509Certificate>()
                override fun checkClientTrusted(c:Array<out X509Certificate>?,t:String?){throw CertificateException()}
                override fun checkServerTrusted(c:Array<out X509Certificate>?,t:String?){
                    val cert=c?.firstOrNull()?:throw CertificateException()
                    cert.checkValidity()
                    if(fingerprint(cert)!=fields[0])throw CertificateException()
                }
            }
            val context=SSLContext.getInstance("TLS").apply{init(null,arrayOf(trust),SecureRandom())}
            (context.socketFactory.createSocket() as SSLSocket).use{socket->
                socket.soTimeout=4500
                socket.connect(InetSocketAddress(uri.host,uri.port),3500)
                socket.startHandshake()
                val id=UUID.randomUUID().toString()
                val req=JSONObject().put("protocolVersion",1).put("requestId",id).put("token",fields[1])
                    .put("command",action).put("parameters",parameters)
                DataOutputStream(socket.outputStream).apply{writeUTF(req.toString());flush()}
                val reply=JSONObject(DataInputStream(socket.inputStream).readUTF())
                require(reply.getString("requestId")==id && reply.getInt("protocolVersion")==1)
                return reply.getJSONObject("result")
            }
        }
    }
}

internal class RemotePanel(private val activity:Activity,private val command:(String,JSONObject)->JSONObject){
    private var server:PeerServer?=null
    private var starting=false
    private var closed=false
    fun close(){closed=true;server?.close();server=null}
    private fun report(value:String){if(!activity.isDestroyed)Toast.makeText(activity,value,Toast.LENGTH_LONG).show()}
    fun menu(){
        val running=server
        val choices=if(running==null)arrayOf("允許另一支手機遙控","遙控另一支手機") else arrayOf("顯示配對資料","遙控另一支手機","停止接受遙控")
        AlertDialog.Builder(activity).setTitle("同一區域網路的手機遙控").setItems(choices){_,i->
            when(i){
                0->if(running!=null)showPair(running.invitation) else start()
                1->{val input=EditText(activity).apply{hint="貼上直播手機的配對資料";isSingleLine=true;isSaveEnabled=false;inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD}
                    AlertDialog.Builder(activity).setTitle("連線到直播手機").setView(input).setNegativeButton("取消",null)
                        .setPositiveButton("連線"){_,_->control(input.text.toString(),"status")}.show()}
                2->{server?.close();server=null;report("已停止接受遙控")}
            }
        }.show()
    }
    private fun start(){
        if(starting || closed)return
        val cm=activity.getSystemService(ConnectivityManager::class.java)
        val ip=cm.allNetworks.filter{network->cm.getNetworkCapabilities(network)?.let{
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)}==true
        }.flatMap{cm.getLinkProperties(it)?.linkAddresses.orEmpty()}.map{it.address}.filterIsInstance<Inet4Address>().firstOrNull()
        if(ip==null){report("請讓兩支手機連到同一個 Wi-Fi 或有線區域網路");return}
        starting=true
        thread{
            val result=runCatching{PeerServer.start(ip){action,params->
                val task=FutureTask(Callable{check(!closed && server!=null);command(action,params)})
                activity.runOnUiThread(task)
                task.get(3,TimeUnit.SECONDS)
            }}
            activity.runOnUiThread{
                starting=false
                result.onSuccess{if(closed)it.close() else{server=it;showPair(it.invitation)}}.onFailure{report("遙控連線服務啟動失敗")}
            }
        }
    }
    private fun showPair(invite:String){
        AlertDialog.Builder(activity).setTitle("配對直播手機").setMessage("將配對資料交給同一區域網路的導播手機。持有這份資料的人可以切換場景與控制麥克風。本次停止遙控後即失效。")
            .setPositiveButton("複製配對資料"){_,_->activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Meikou 遙控配對",invite))}
            .setNegativeButton("關閉",null).show()
    }
    private fun control(invite:String,action:String,params:JSONObject=JSONObject()){
        thread{
            val result=runCatching{PeerServer.send(invite,action,params)}
            activity.runOnUiThread{
                if(closed || activity.isFinishing)return@runOnUiThread
                result.onFailure{report("遙控連線失敗，請確認區域網路及配對資料")}.onSuccess{state->
                    val scenes=state.getJSONArray("scenes")
                    val list=(0 until scenes.length()).map{scenes.getJSONObject(it)}
                    val muted=state.getBoolean("muted")
                    val choices=list.map{"切換至 "+it.getString("name")}+if(muted)"開啟麥克風" else "麥克風靜音"
                    AlertDialog.Builder(activity).setTitle("目前場景 · "+state.getString("active"))
                        .setItems(choices.toTypedArray()){_,index->if(index==list.size)control(invite,"audio.mute",JSONObject().put("muted",!muted))
                            else control(invite,"scene.activate",JSONObject().put("sceneId",list[index].getString("id")))}
                        .setNegativeButton("離開遙控",null).show()
                }
            }
        }
    }
}
