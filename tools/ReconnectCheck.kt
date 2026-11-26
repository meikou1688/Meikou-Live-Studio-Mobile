package tw.meikou.live

import java.io.DataInputStream
import java.io.File
import java.net.URI

/** Publish actual encoded frames while the external test restarts its receiver. */
fun main(args:Array<String>){
    var lost=false
    var resumed=false
    ReconnectingRtmp(URI(args[0]),{up->if(up)resumed=true else lost=true},{}).use{client->
        client.connect()
        val start=System.nanoTime()
        repeat(4){cycle->
            DataInputStream(File(args[1]).inputStream().buffered()).use{input->
                fun u24()=(input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
                input.skipBytes(9)
                while(input.available()>4){
                    input.readInt()
                    val type=input.readUnsignedByte()
                    val size=u24()
                    val time=u24() or (input.readUnsignedByte() shl 24)
                    u24()
                    val bytes=ByteArray(size);input.readFully(bytes)
                    if(type!=8 && type!=9)continue
                    if(cycle>0 && bytes[1].toInt()==0)continue
                    if(type==9 && bytes[1].toInt()==2)continue
                    val timestamp=cycle*3100+time
                    val wait=timestamp-(System.nanoTime()-start)/1000000
                    if(wait>0)Thread.sleep(wait)
                    client.media(type,timestamp,bytes)
                }
            }
        }
        check(lost && resumed && client.sentBoth())
        println("PASS: RTMP loss and recovery on the same capture session")
    }
}
