package tw.meikou.live

import java.io.*
import java.net.*
import javax.net.ssl.*
import kotlin.concurrent.thread

/** Single publishing session. All writes share a lock with control replies. */
class Rtmp(private val endpoint: URI) : Closeable {
    @Volatile private var socket: Socket? = null
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    private var inboundChunk = 128
    private var stream = 0
    private var received = 0L
    private var acknowledged = 0L
    private var window = 2500000
    private class Header(var time:Int=0,var delta:Int=0,var length:Int=0,var type:Int=0,var ext:Boolean=false,var bytes:ByteArray=byteArrayOf(),var used:Int=0)
    private val headers = mutableMapOf<Int,Header>()
    @Volatile private var closed = false

    fun connect() {
        val tls = endpoint.scheme == "rtmps"
        require(tls || endpoint.scheme == "rtmp")
        val host = requireNotNull(endpoint.host)
        val path = endpoint.rawPath.trimStart('/')
        require(path.contains('/') && !path.endsWith('/'))
        val app = path.substringBeforeLast('/')
        val key = path.substringAfterLast('/') + (endpoint.rawQuery?.let { "?" + it } ?: "")
        val port = if (endpoint.port > 0) endpoint.port else if (tls) 443 else 1935
        val tcp = Socket()
        socket = tcp
        check(!closed)
        tcp.connect(InetSocketAddress(host, port), 7000)
        tcp.soTimeout = 8000
        val s = if (tls) {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val secure = factory.createSocket(tcp, host, port, true) as SSLSocket
            socket = secure
            secure.soTimeout = 8000
            secure.sslParameters = secure.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
                if (!host.contains(':') && !host.matches(Regex("[0-9.]+"))) {
                    serverNames = listOf(SNIHostName(host))
                }
            }
            secure.startHandshake()
            secure
        } else tcp
        input=DataInputStream(s.getInputStream().buffered())
        output=DataOutputStream(s.getOutputStream().buffered())
        publish(app,key,endpoint.scheme+"://"+endpoint.rawAuthority+"/"+app)
    }
    internal fun publishOverStreams(source:InputStream,sink:OutputStream){
        input=DataInputStream(source);output=DataOutputStream(sink)
        publish("live","check","rtmp://localhost/live")
    }
    private fun publish(app:String,key:String,tcUrl:String){
        val c1=ByteArray(1536).also { java.security.SecureRandom().nextBytes(it); it.fill(0,0,8) }
        output.writeByte(3); output.write(c1); output.flush()
        check(input.readUnsignedByte()==3)
        val server=ByteArray(1536); input.readFully(server)
        output.write(server); output.flush(); input.readFully(server)
        command(0,"connect",1.0,mapOf("app" to app,"tcUrl" to tcUrl,"type" to "nonprivate","flashVer" to "FMLE/3.0"))
        awaitResult(1.0)
        command(0,"createStream",2.0,null)
        stream=(awaitResult(2.0).last() as Number).toInt()
        command(stream,"publish",0.0,null,key,"live")
        while(true) {
            val cmd=readCommand() ?: continue
            if (cmd.firstOrNull() == "_error") throw RtmpRejected(publishing = true)
            if(cmd.firstOrNull()=="onStatus") {
                val info = cmd.lastOrNull() as? Map<*, *> ?: continue
                when {
                    info["code"] == "NetStream.Publish.Start" -> break
                    info["level"] == "error" || info["code"] in setOf("NetStream.Publish.BadName", "NetStream.Publish.Denied", "NetStream.Failed") ->
                        throw RtmpRejected(publishing = true)
                }
            }
        }
        socket?.soTimeout=0
        thread(isDaemon=true,name="RTMP control") { try { while(!closed) readCommand() } catch(e:Exception){ close() } }
    }

    private fun awaitResult(tx:Double):List<Any?> {
        while(true) {
            val args=readCommand() ?: continue
            if(args.getOrNull(1)==tx) {
                if (args.first() == "_error") throw RtmpRejected(publishing = false)
                if(args.first()=="_result") return args
            }
        }
    }
    private fun num24(i:DataInputStream)= (i.readUnsignedByte() shl 16) or (i.readUnsignedByte() shl 8) or i.readUnsignedByte()
    private fun medium(o:DataOutputStream,n:Int){o.writeByte(n ushr 16);o.writeShort(n)}
    private fun readCommand():List<Any?>? {
        val basic=input.readUnsignedByte()
        val fmt=basic ushr 6
        val id=when(val n=basic and 63){0->64+input.readUnsignedByte();1->64+input.readUnsignedByte()+256*input.readUnsignedByte();else->n}
        val h=headers.getOrPut(id){Header()}
        val fresh=fmt!=3 || h.used==h.length
        var time=0
        if(fmt<3){time=num24(input);h.ext=time==0xffffff}
        if(fmt<2){h.length=num24(input);h.type=input.readUnsignedByte()}
        if(fmt==0) input.readInt()
        if(h.ext) time=input.readInt()
        if(fresh){
            if(fmt==0){h.time=time;h.delta=0}else{if(fmt!=3)h.delta=time;h.time+=h.delta}
            require(h.length<=4*1024*1024)
            h.bytes=ByteArray(h.length);h.used=0
        }
        val count=minOf(inboundChunk,h.length-h.used)
        input.readFully(h.bytes,h.used,count);h.used+=count
        received+=count+1+(if(fmt==0)11 else if(fmt==1)7 else if(fmt==2)3 else 0)+(if(h.ext)4 else 0)+(if((basic and 63)==0)1 else if((basic and 63)==1)2 else 0)
        if(received-acknowledged>=window){send(2,3,0,0,buffer{it.writeInt(received.toInt())});acknowledged=received}
        if(h.used!=h.length)return null
        val d=DataInputStream(h.bytes.inputStream())
        when(h.type){
            1->inboundChunk=d.readInt().also{require(it>0)}
            5->window=d.readInt().coerceAtLeast(1)
            4->if(d.readUnsignedShort()==6)send(2,4,0,0,buffer{it.writeShort(7);it.writeInt(d.readInt())})
            20,17->{if(h.type==17)d.readByte();return buildList{while(d.available()>0)add(readAmf(d,d.readUnsignedByte(),0))}}
        }
        return null
    }
    private fun readAmf(d:DataInputStream,type:Int,depth:Int):Any? {
        require(depth<20)
        return when(type){
            0->d.readDouble();1->d.readBoolean();2->d.readUTF();5,6->null
            3,8->{if(type==8)d.readInt();buildMap<String,Any?> {while(true){val key=d.readUTF();val t=d.readUnsignedByte();if(key.isEmpty()&&t==9)break;put(key,readAmf(d,t,depth+1))}}}
            else->throw IOException("AMF "+type)
        }
    }
    private fun amf(d:DataOutputStream,v:Any?){when(v){
        null->d.writeByte(5)
        is String->{d.writeByte(2);val b=v.toByteArray();require(b.size<=65535);d.writeShort(b.size);d.write(b)}
        is Double->{d.writeByte(0);d.writeDouble(v)}
        is Map<*,*>->{d.writeByte(3);v.forEach{(k,x)->d.writeUTF(k.toString());amf(d,x)};d.writeShort(0);d.writeByte(9)}
    }}
    private fun command(stream:Int,vararg values:Any?)=send(3,20,stream,0,buffer{d->values.forEach{amf(d,it)}})
    fun media(type:Int,ms:Int,data:ByteArray)=send(if(type==8)4 else 6,type,stream,ms,data)
    @Synchronized private fun send(channel:Int,type:Int,stream:Int,ms:Int,data:ByteArray){
        check(!closed)
        output.writeByte(channel);medium(output,minOf(ms,0xffffff));medium(output,data.size);output.writeByte(type)
        output.writeInt(Integer.reverseBytes(stream));if(ms>=0xffffff)output.writeInt(ms)
        var pos=0
        while(pos<data.size){
            if(pos>0){output.writeByte(192 or channel);if(ms>=0xffffff)output.writeInt(ms)}
            val count=minOf(128,data.size-pos);output.write(data,pos,count);pos+=count
        }
        output.flush()
    }
    override fun close(){closed=true;runCatching{socket?.close()}}
}
internal fun buffer(f:(DataOutputStream)->Unit):ByteArray=ByteArrayOutputStream().let{f(DataOutputStream(it));it.toByteArray()}


internal class RtmpRejected(val publishing: Boolean) : IOException()

// Never forward server descriptions, URLs or exception messages: they can contain stream keys.
internal fun rtmpFailureText(error: Exception): String = when (error) {
    is RtmpRejected -> if (error.publishing) "伺服器拒絕發布。請確認這場直播的串流金鑰，以及是否已有其他編碼器使用。" else "伺服器拒絕連線。請核對推流網址與伺服器授權。"
    is UnknownHostException -> "找不到推流伺服器，請檢查主機名稱與網路。"
    is SSLException -> "RTMPS 加密連線失敗，請核對伺服器網址、連接埠及憑證。"
    is SocketTimeoutException -> "推流伺服器回應逾時，請檢查網路與連接埠。"
    is ConnectException, is NoRouteToHostException -> "無法連上推流伺服器，請檢查網路與連接埠。"
    else -> "伺服器未確認發布，請核對推流網址、連接埠與串流金鑰。"
}
