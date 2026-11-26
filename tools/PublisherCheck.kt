package tw.meikou.live
import java.io.DataInputStream
import java.io.File
import java.net.URI

fun main(args:Array<String>){
    val file=DataInputStream(File(args[1]).inputStream().buffered())
    fun u24()=(file.readUnsignedByte() shl 16) or (file.readUnsignedByte() shl 8) or file.readUnsignedByte()
    file.skipBytes(9)
    Rtmp(URI(args[0])).use{sender->
        if(args[0]=="pipe")sender.publishOverStreams(System.`in`,System.out) else sender.connect()
        while(file.available()>4){
            file.readInt()
            val type=file.readUnsignedByte();val size=u24();val time=u24() or (file.readUnsignedByte() shl 24)
            u24()
            val payload=ByteArray(size);file.readFully(payload)
            if(type==9 && payload.size>=6 && payload[1].toInt() in 0..1){
                val src=DataInputStream(payload.inputStream())
                src.skipBytes(5)
                val rebuilt=if(payload[1].toInt()==0){
                    src.skipBytes(5)
                    val sps=List(src.readUnsignedByte() and 31){ByteArray(src.readUnsignedShort()).also{src.readFully(it)}}
                    val pps=List(src.readUnsignedByte()){ByteArray(src.readUnsignedShort()).also{src.readFully(it)}}
                    Avc.config(sps+pps)
                }else{
                    val annex=buffer{out->while(src.available()>0){
                        val n=src.readInt();require(n>0 && n<=src.available());out.writeInt(1)
                        out.write(ByteArray(n).also{src.readFully(it)})
                    }}
                    Avc.frame(annex,payload[0].toInt() ushr 4==1)
                }
                sender.media(type,time,rebuilt)
            }else if(type==8)sender.media(type,time,payload)
        }
    }
}

