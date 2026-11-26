#!/usr/bin/env bash
set -euo pipefail
work=$(mktemp -d)
listener=
relay=
cleanup() {
  if [[ -n "$relay" ]]; then kill "$relay" 2>/dev/null || true; fi
  if [[ -n "$listener" ]]; then kill "$listener" 2>/dev/null || true; fi
  rm -f "$work/cert.pem" "$work/key.pem" "$work/trust.p12"
  rmdir "$work"
}
trap cleanup EXIT
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj '/CN=localhost' \
  -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' -keyout "$work/key.pem" -out "$work/cert.pem"
keytool -importcert -noprompt -alias test-endpoint -file "$work/cert.pem" -keystore "$work/trust.p12" -storepass changeit
ffmpeg -nostdin -loglevel error -y -listen 1 -i rtmp://127.0.0.1:19385/live/check -c copy build/rtmps-received.mkv &
listener=$!
socat "OPENSSL-LISTEN:19386,bind=127.0.0.1,reuseaddr,cert=$work/cert.pem,key=$work/key.pem,verify=0" TCP:127.0.0.1:19385 &
relay=$!
sleep 1
java "-Djavax.net.ssl.trustStore=$work/trust.p12" -Djavax.net.ssl.trustStorePassword=changeit \
  -cp "build/check.jar:$GRADLE_HOME/lib/kotlin-stdlib-2.0.21.jar" tw.meikou.live.PublisherCheckKt \
  rtmps://localhost:19386/live build/chart.flv check
wait "$listener"
listener=
python3 - <<'PY'
import json, sys
sys.path.insert(0,"tools")
from test_transport import verify
from build import out
report = out / "transport-results.json"
results = json.loads(report.read_text())
results["rtmps"] = verify(out / "rtmps-received.mkv")
report.write_text(json.dumps(results, indent=2))
PY
