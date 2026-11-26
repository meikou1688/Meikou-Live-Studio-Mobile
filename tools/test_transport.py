"""Encode a deterministic test chart + tone, publish via APK's Kotlin RTMP class, verify received media."""
import json, socket, time, subprocess as sp
from build import root,out,compile_kotlin,java,gradle,os
def run(*args):sp.run(list(map(str,args)),check=True,timeout=40)
def verify(path):
    data=json.loads(sp.check_output(["ffprobe","-v","error","-count_frames","-show_streams","-of","json",str(path)]))
    observed={s["codec_name"]:int(s["nb_read_frames"]) for s in data["streams"]}
    assert observed.get("h264",0)>=80 and observed.get("aac",0)>=100,observed
    run("ffmpeg","-v","error","-xerror","-i",path,"-f","null","-")
    return observed
def test():
    out.mkdir(exist_ok=True)
    geometry=out/"camera-geometry-check.jar"
    compile_kotlin([root/"app/src/main/java/tw/meikou/live/CameraGeometry.kt",root/"tools/CameraGeometryCheck.kt"],geometry)
    run(java,"-cp",str(geometry)+os.pathsep+str(gradle/"lib/kotlin-stdlib-2.0.21.jar"),"tw.meikou.live.CameraGeometryCheckKt")
    timeline=out/"timeline-check.jar"
    compile_kotlin([root/"app/src/main/java/tw/meikou/live/CaptureTimeline.kt",root/"tools/TimelineCheck.kt"],timeline)
    run(java,"-cp",str(timeline)+os.pathsep+str(gradle/"lib/kotlin-stdlib-2.0.21.jar"),"tw.meikou.live.TimelineCheckKt")
    pcm=out/"mute-check.jar"
    compile_kotlin([root/"app/src/main/java/tw/meikou/live/Pcm.kt",root/"tools/MuteCheck.kt"],pcm)
    run(java,"-cp",str(pcm)+os.pathsep+str(gradle/"lib/kotlin-stdlib-2.0.21.jar"),"tw.meikou.live.MuteCheckKt")
    fixture=out/"chart.flv"
    run("ffmpeg","-hide_banner","-loglevel","error","-y","-f","lavfi","-i","testsrc2=size=640x360:rate=30","-f","lavfi","-i","sine=frequency=880:sample_rate=48000","-t","3","-c:v","libx264","-profile:v","baseline","-g","30","-c:a","aac",fixture)
    jar=out/"check.jar"
    folder=root/"app/src/main/java/tw/meikou/live"
    compile_kotlin([folder/"Rtmp.kt",folder/"Avc.kt",folder/"StreamDestination.kt",root/"tools/PublisherCheck.kt",root/"tools/StreamDestinationCheck.kt"],jar)
    run(java,"-cp",str(jar)+os.pathsep+str(gradle/"lib/kotlin-stdlib-2.0.21.jar"),"tw.meikou.live.StreamDestinationCheckKt")
    from pipe_receiver import receive
    sample=out/"pipe-received.flv"
    receive(fixture,sample)
    results={"pipe":verify(sample)}
    (out/"transport-results.json").write_text(json.dumps(results,indent=2))
    for protocol in ["rtmp","srt"]:
        with socket.socket(type=socket.SOCK_STREAM if protocol=="rtmp" else socket.SOCK_DGRAM) as sock:
            sock.bind(("127.0.0.1",0));port=sock.getsockname()[1]
        base=protocol+"://127.0.0.1:"+str(port)
        url=base+("/live/check" if protocol=="rtmp" else "?mode=listener&transtype=live")
        dest=out/(protocol+"-received.mkv")
        receiver=sp.Popen(["ffmpeg","-loglevel","error","-y",*(["-listen","1"] if protocol=="rtmp" else []),"-i",url,"-c","copy",str(dest)])
        try:
            time.sleep(1)
            if protocol=="rtmp":run(java,"-cp",str(jar)+os.pathsep+str(gradle/"lib/kotlin-stdlib-2.0.21.jar"),"tw.meikou.live.PublisherCheckKt",base+"/live",fixture,"check")
            else:run("ffmpeg","-loglevel","error","-re","-i",fixture,"-c","copy","-f","mpegts",base+"?mode=caller&transtype=live")
            receiver.wait(10)
            results[protocol]=verify(dest)
        except Exception as exc:
            results[protocol]={"error":type(exc).__name__}
        finally:
            if receiver.poll() is None:receiver.kill();receiver.wait()
        (out/"transport-results.json").write_text(json.dumps(results,indent=2))
    if any("error" in x for x in results.values()):raise SystemExit(1)
if __name__=="__main__":test()

