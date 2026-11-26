package tw.meikou.live

fun main(){
    for(protocol in listOf("rtmp","rtmps")){
        val server="$protocol://example.test:443/live2"
        val complete="$server/test-key"
        check(streamDestination(server,"test-key").toString()==complete)
        check(streamDestination("$server/","test-key").toString()==complete)
        check(streamDestination(complete,"").toString()==complete)
        check(streamDestination("$server?region=tw","test-key?token=a%2Fb").toString()=="$complete?region=tw&token=a%2Fb")
        check(runCatching{streamDestination(server,"")}.isFailure)
        check(runCatching{streamDestination(server,"bad#fragment")}.isFailure)
    }
    check(runCatching{streamDestination("https://example.test/live","key")}.isFailure)
    check(primaryDestination("srt://example.test:9000/mobile","").scheme=="srt")
    check(primaryDestination("srt://example.test:9000?streamid=mobile&latency=120","").port==9000)
    for(url in listOf("srt://example.test/mobile","srt://example.test:9000","srt://example.test:9000/a?mode=listener","srt://example.test:9000/a?passphrase=short","srt://example.test:9000/a?latency=-2")){
        check(runCatching{primaryDestination(url,"")}.isFailure)
    }
    check(runCatching{primaryDestination("srt://example.test:9000/a","key")}.isFailure)
    println("PASS: 21 destination cases")
}
