#!/usr/bin/env bash
set -euo pipefail
url=rtmp://127.0.0.1:19482/live/restore
ffmpeg -nostdin -loglevel error -y -listen 1 -i "$url" -t 2 -c copy build/before-outage.mkv &
first=$!
publisher=
second=
trap 'for pid in "$first" "$second" "$publisher"; do [[ -z "$pid" ]] || kill "$pid" 2>/dev/null || true; done' EXIT
sleep 1
java -cp "build/check.jar:$GRADLE_HOME/lib/kotlin-stdlib-2.0.21.jar" tw.meikou.live.ReconnectCheckKt "$url" build/chart.flv &
publisher=$!
wait "$first"
first=
sleep 1
ffmpeg -nostdin -loglevel error -y -listen 1 -i "$url" -c copy build/after-outage.mkv &
second=$!
wait "$publisher"
publisher=
wait "$second"
second=
for f in before-outage after-outage; do
  ffmpeg -v error -xerror -i "build/$f.mkv" -map 0:v -map 0:a -f null -
  ffprobe -v error -count_frames -show_entries stream=codec_name,nb_read_frames -of json "build/$f.mkv"
done
