package tw.meikou.live

internal object Avc {
    fun units(bytes:ByteArray):List<ByteArray>{
        val result=ArrayList<ByteArray>()
        var start=-1;var i=0
        while(i+2<bytes.size){
            val n=if(bytes[i].toInt()==0 && bytes[i+1].toInt()==0){
                if(bytes[i+2].toInt()==1)3 else if(i+3<bytes.size && bytes[i+2].toInt()==0 && bytes[i+3].toInt()==1)4 else 0
            }else 0
            if(n>0){if(start>=0 && start<i)result.add(bytes.copyOfRange(start,i));i+=n;start=i}else i++
        }
        if(start<0)return listOf(bytes)
        if(start<bytes.size)result.add(bytes.copyOfRange(start,bytes.size))
        return result
    }
    fun config(nals:List<ByteArray>):ByteArray {
        val s=nals.first{it[0].toInt() and 31==7};val p=nals.first{it[0].toInt() and 31==8}
        return buffer{it.write(byteArrayOf(0x17,0,0,0,0,1,s[1],s[2],s[3],-1,0xe1.toByte()));it.writeShort(s.size);it.write(s);it.writeByte(1);it.writeShort(p.size);it.write(p)}
    }
    fun frame(bytes:ByteArray,key:Boolean)=buffer{d->
        d.writeByte(if(key)0x17 else 0x27);d.write(byteArrayOf(1,0,0,0))
        units(bytes).forEach{d.writeInt(it.size);d.write(it)}
    }
}

