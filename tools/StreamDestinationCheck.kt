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
    println("PASS: 13 destination cases")
}
