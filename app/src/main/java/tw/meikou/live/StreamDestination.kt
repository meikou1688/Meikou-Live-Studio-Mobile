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

internal fun primaryDestination(server:String,key:String):URI {
    val uri=URI(server.trim())
    if(uri.scheme!="srt")return streamDestination(server,key)
    require(uri.host!=null && uri.port in 1..65535 && uri.rawUserInfo==null && uri.fragment==null && key.isBlank())
    val query=uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
        val parts=it.split('=',limit=2)
        require(parts.size==2)
        parts[0] to java.net.URLDecoder.decode(parts[1],"UTF-8")
    }
    require(query.map{it.first}.distinct().size==query.size)
    val options=query.toMap()
    require(options.keys.all { it in setOf("streamid","latency","mode") })
    require(options["mode"] in listOf(null,"caller"))
    require(options["latency"]?.let { it.toIntOrNull() in 1..60000 }!=false)
    require(uri.path.trim('/').isNotBlank() || !options["streamid"].isNullOrBlank())
    return uri
}
