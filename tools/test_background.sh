#!/usr/bin/env bash
set -euo pipefail
if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
    echo "This test changes scene data and requires a disposable Android emulator." >&2
    exit 1
fi
mkdir -p build
variant=${MEIKOU_TEST_VARIANT:-debug}
package=tw.meikou.live
if [[ "$variant" == preview ]]; then package=tw.meikou.live.preview; fi
adb install -r "app/build/outputs/apk/$variant/app-$variant.apk"
adb install -r "app/build/outputs/apk/androidTest/$variant/app-$variant-androidTest.apk"
for permission in CAMERA RECORD_AUDIO POST_NOTIFICATIONS; do
  adb shell pm grant "$package" "android.permission.$permission"
done
fixture_dir="/sdcard/Android/data/$package/files"
adb shell mkdir -p "$fixture_dir"
for color in lime magenta; do
    ffmpeg -v error -y -f lavfi -i "color=c=$color:s=160x96:r=12" -t 1.5 -c:v libx264 -pix_fmt yuv420p -movflags +faststart "build/$color.mp4"
    adb push "build/$color.mp4" "$fixture_dir/$color.mp4"
done
ffmpeg -v error -y -f lavfi -i sine=frequency=997:sample_rate=44100 -ac 2 -t 1.5 -c:a aac build/melody.m4a
adb push build/melody.m4a "$fixture_dir/melody.m4a"
adb reverse tcp:1935 tcp:1935
protocol=${1:-rtmp}
listener=(-listen 1 -i rtmp://127.0.0.1:1935/live/integration)
export MEIKOU_EXPECT_VIDEO=h264
if [[ $protocol == srt-hevc ]]; then MEIKOU_EXPECT_VIDEO=hevc; fi
if [[ $protocol == srt* ]]; then listener=(-i 'srt://0.0.0.0:19001?mode=listener&transtype=live'); fi
ffmpeg -nostdin -hide_banner -loglevel warning -y "${listener[@]}" -c copy build/background.mkv >build/receiver.log 2>&1 &
receiver=$!
trap 'kill "$receiver" 2>/dev/null || true' EXIT
extra_receivers=()
if [[ $protocol == rtmp ]]; then
    for port in {1936..1940}; do
        adb reverse tcp:$port tcp:$port
        ffmpeg -nostdin -v error -y -listen 1 -i "rtmp://127.0.0.1:$port/live/integration" -c copy "build/lane-$port.mkv" 2>"build/lane-$port.log" &
        extra_receivers+=($!)
    done
fi
trap 'kill "$receiver" "${extra_receivers[@]}" 2>/dev/null || true' EXIT
sleep 1
adb shell am instrument -w -e protocol "$protocol" "$package.test/tw.meikou.live.BackgroundCheck" | tee build/instrumentation.txt
if grep -q 'MEIKOU_CODEC_UNAVAILABLE hevc' build/instrumentation.txt; then
    if [[ $protocol != srt-hevc ]]; then
        echo "Only the optional HEVC hardware run may report an unavailable codec." >&2
        exit 1
    fi
    kill "$receiver" 2>/dev/null || true
    wait "$receiver" 2>/dev/null || true
    python3 - <<'PY'
import json
from pathlib import Path
Path("build/background-srt-hevc-results.json").write_text(json.dumps({
    "hevc": {
        "status": "not_supported",
        "reason": "The Android emulator exposes no HEVC encoder and camera-size combination."
    }
}, indent=2))
PY
    echo "HEVC SRT hardware run recorded as unsupported on this emulator; no encoding claim was made."
    exit 0
fi
if ! grep -q MEIKOU_BACKGROUND_OK build/instrumentation.txt; then
    adb logcat -d -s MeikouCapture:I MeikouRemote:W MediaCodec:E CCodec:E AndroidRuntime:E | tail -120
    cat build/receiver.log
    exit 1
fi
for _ in {1..20}; do kill -0 "$receiver" 2>/dev/null || break; sleep 0.5; done
if [[ $protocol == rtmp ]]; then
    for port in {1936..1940}; do
        ffmpeg -v error -xerror -i "build/lane-$port.mkv" -map 0:v -map 0:a -f null -
        ffprobe -v error -count_frames -show_entries stream=codec_name,nb_read_frames -of json "build/lane-$port.mkv" > "build/lane-$port.json"
        python3 -c 'import json,sys; tracks={s["codec_name"]:int(s["nb_read_frames"]) for s in json.load(open(sys.argv[1]))["streams"]}; print(tracks); assert tracks["aac"]>100 and tracks["h264"]>100' "build/lane-$port.json"
    done
fi
recording=$(adb shell 'ls -t "/sdcard/Movies/Meikou Live/"Meikou-*.mp4' | head -1 | tr -d '\r')
adb pull "$recording" "build/$protocol-local.mp4"
ffmpeg -v error -xerror -i "build/$protocol-local.mp4" -map 0:a -map 0:v -f null -
ffmpeg -v error -xerror -i build/background.mkv -f null -
# The live overlap probes temporarily cover the QR. Verify its geometry in the settled scene.
ffmpeg -v error -y -sseof -2 -i build/background.mkv -frames:v 1 build/overlay.png
python3 - <<'PY'
import cv2
import numpy as np
im=cv2.imread("build/overlay.png")
value,points,_=cv2.QRCodeDetector().detectAndDecode(im)
assert value=="Meikou-camera-check", "receiver could not read QR layer"
quad=points[0]
center=quad.mean(axis=0)/np.array([im.shape[1],im.shape[0]])
edges=np.linalg.norm(quad-np.roll(quad,1,axis=0),axis=1)
assert np.max(np.abs(center-[.62,.55]))<.03,center
assert edges.max()/edges.min()<1.08,edges
assert .17<edges.mean()/im.shape[1]<.27,edges
print("Decoded receiver QR at",center,"edge lengths",edges)
h,w=im.shape[:2]
camera=im[int(.1*h):int(.35*h),int(.12*w):int(.8*w)].astype(int)
assert np.percentile(camera.max(axis=2)-camera.min(axis=2),99)<12,"camera grayscale filter failed"
assert camera.std()>3,"camera sample lacks visible detail"
print("Decoded camera grayscale; separate scene overlays retain their colors")
import subprocess
marker_rgb=subprocess.check_output(["ffmpeg","-v","error","-i","build/background.mkv","-an",
    "-vf","fps=8,crop=4:4:iw*0.83:ih*0.5,scale=1:1","-pix_fmt","rgb24","-f","rawvideo","-"])
marker_pixels=np.frombuffer(marker_rgb,dtype="uint8").reshape(-1,3)
yellow=(marker_pixels[:,0]>180) & (marker_pixels[:,1]>180) & (marker_pixels[:,2]<70)
assert (yellow[:-1] & yellow[1:]).any() and not yellow[-8:].any(), "live edge removal did not reach the receiver"
print("Decoded the yellow overlay on air and verified its removal while streaming continued")
pixels=subprocess.check_output(["ffmpeg","-v","error","-i","build/background.mkv","-an","-vf",
    "fps=2,crop=4:4:iw*0.18:ih*0.5,scale=1:1","-pix_fmt","rgb24","-f","rawvideo","-"])
colors=np.frombuffer(pixels,dtype="uint8").reshape((-1,3)).astype(int)
phases=[0 if r>b+80 else 1 for r,g,b in colors if max(r,b)>g+70 and abs(r-b)>80]
assert len(phases)>10 and sum(a!=b for a,b in zip(phases,phases[1:]))>=3,"slide sequence missing at receiver"
print("Decoded alternating image playlist at the receiver")
clip_pixels=subprocess.check_output(["ffmpeg","-v","error","-i","build/background.mkv","-an","-vf",
    "fps=2,crop=4:4:iw*0.85:ih*0.82,scale=1:1","-pix_fmt","rgb24","-f","rawvideo","-"])
clip_rgb=np.frombuffer(clip_pixels,dtype="uint8").reshape((-1,3)).astype(int)
clip_states=[0 if g>r+60 else 1 for r,g,b in clip_rgb if (g>r+60 and g>b+60) or (r>g+60 and b>g+60)]
assert len(clip_states)>10 and sum(a!=b for a,b in zip(clip_states,clip_states[1:]))>=3,"video playlist missing at receiver"
print("Decoded both video clips repeating at the receiver")
audio=subprocess.check_output(["ffmpeg","-v","error","-ss","4","-i","build/background.mkv","-t","1","-vn","-ar","48000","-ac","1","-f","s16le","-"])
pcm=np.frombuffer(audio,dtype="<i2").astype(float)
spectrum=np.abs(np.fft.rfft(pcm*np.hanning(len(pcm))))
peak=int(spectrum.argmax())*48000/len(pcm)
assert abs(peak-997)<4 and np.sqrt(np.mean(pcm*pcm))>300,(peak,pcm.max())
print("Decoded mixed stereo playlist tone at",peak,"Hz")
PY
ffprobe -v error -show_streams -show_packets -show_entries stream=index,codec_name:packet=stream_index,pts_time -of json build/background.mkv >build/background-packets.json
python3 - <<'PY'
import json
from pathlib import Path
data=json.loads(Path("build/background-packets.json").read_text())
stats={}
for stream in data["streams"]:
    times=sorted(float(p["pts_time"]) for p in data["packets"] if p["stream_index"]==stream["index"])
    span=times[-1]-times[0]
    gap=max(b-a for a,b in zip(times,times[1:]))
    stats[stream["codec_name"]]={"seconds":span,"max_gap":gap,"packets":len(times)}
    assert span>=14 and gap<2,stats
import os
assert set(stats)=={os.environ["MEIKOU_EXPECT_VIDEO"],"aac"},stats
Path("build/background-results.json").write_text(json.dumps(stats,indent=2))
print(stats)
PY
cp build/background-results.json "build/background-$protocol-results.json"
python3 tools/check_session_timing.py "$protocol"
