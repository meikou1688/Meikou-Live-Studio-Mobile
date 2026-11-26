#!/usr/bin/env bash
set -euo pipefail
mkdir -p build
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
for permission in CAMERA RECORD_AUDIO POST_NOTIFICATIONS; do
  adb shell pm grant tw.meikou.live "android.permission.$permission"
done
adb reverse tcp:1935 tcp:1935
protocol=${1:-rtmp}
listener=(-listen 1 -i rtmp://127.0.0.1:1935/live/integration)
if [[ $protocol == srt ]]; then listener=(-i 'srt://0.0.0.0:19001?mode=listener&transtype=live'); fi
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
adb shell am instrument -w -e protocol "$protocol" tw.meikou.live.test/tw.meikou.live.BackgroundCheck | tee build/instrumentation.txt
grep -q MEIKOU_BACKGROUND_OK build/instrumentation.txt
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
assert set(stats)=={"h264","aac"},stats
Path("build/background-results.json").write_text(json.dumps(stats,indent=2))
print(stats)
PY
cp build/background-results.json "build/background-$protocol-results.json"
