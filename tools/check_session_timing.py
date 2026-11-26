"""Compare decoded packet timelines of the MP4 and every live receiver."""
import json
import subprocess
import sys
from pathlib import Path

root = Path("build")
protocol = sys.argv[1]
sources = [root / "background.mkv", root / (protocol + "-local.mp4")]
if protocol == "rtmp":
    sources.extend(root / f"lane-{port}.mkv" for port in range(1936, 1941))
results = {}
for source in sources:
    probe = subprocess.check_output(["ffprobe", "-v", "error", "-show_packets", "-show_streams",
        "-show_entries", "packet=stream_index,pts_time:stream=index,codec_name", "-of", "json", str(source)])
    data = json.loads(probe)
    clocks = {}
    for stream in data["streams"]:
        times = sorted(float(p["pts_time"]) for p in data["packets"] if p["stream_index"] == stream["index"])
        clocks[stream["codec_name"]] = (times[0], times[-1])
    video, audio = clocks["h264"], clocks["aac"]
    skew = round((video[1]-video[0])-(audio[1]-audio[0]), 3)
    results[source.name] = {"video_seconds": round(video[1]-video[0], 3),
        "audio_seconds": round(audio[1]-audio[0], 3), "duration_difference": skew}
    print(source.name, results[source.name], flush=True)
    # The RTMP and SRT receivers may wait for the first key frame.
    assert abs(skew) < 1.0 and min(video[1]-video[0], audio[1]-audio[0]) >= 14, results[source.name]
(root / (protocol+"-timing.json")).write_text(json.dumps(results, indent=2))
