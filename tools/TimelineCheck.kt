package tw.meikou.live

fun main(){
    var requested=0
    val timeline=CaptureTimeline { requested++ }
    val output=mutableListOf<Frame>()
    fun feed(audio:Boolean,time:Long,key:Boolean=false){
        timeline.accept(Frame(audio,time,if(key)1 else 0,byteArrayOf(1)),output::add)
    }
    feed(false,0,true)
    feed(true,900000)
    check(requested==1 && output.isEmpty())
    feed(false,920000)
    feed(false,1000000,true)
    feed(true,1010000)
    feed(false,1040000)
    feed(true,1050000)
    feed(true,1500000)
    check(output.map{it.pts}==listOf(0L,10000L,40000L))
    check(output.first().flags==1)
    println("PASS: keyframe start, trim audio lead-in and stalled video tail")
}
