package tw.meikou.live

import java.net.URI

internal fun streamDestination(server:String,streamKey:String):URI {
    val base=URI(server.trim())
    require(base.scheme in listOf("rtmp","rtmps") && base.host!=null && base.userInfo==null && base.fragment==null)
    val key=streamKey.trim()
    if(key.isEmpty()){
        require(base.rawPath.trim('/').contains('/') && !base.rawPath.endsWith('/'))
        return base
    }
    require(!key.startsWith('/') && !key.contains("://") && !key.contains('#'))
    val path=key.substringBefore('?')
    require(path.isNotEmpty() && !path.endsWith('/') && base.rawPath.trim('/').isNotEmpty())
    val query=listOfNotNull(base.rawQuery,key.substringAfter('?',"").ifEmpty{null}).joinToString("&")
    return URI(base.scheme+"://"+base.rawAuthority+base.rawPath.trimEnd('/')+"/"+path+
        if(query.isEmpty()) "" else "?"+query)
}
