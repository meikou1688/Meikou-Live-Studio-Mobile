#!/usr/bin/env bash
set -euo pipefail
work=$(mktemp -d)
listener=
relay=
cleanup() {
  if [[ -n "$relay" ]]; then kill "$relay" 2>/dev/null || true; fi
  if [[ -n "$listener" ]]; then kill "$listener" 2>/dev/null || true; fi
  rm -f "$work/cert.pem" "$work/key.pem" "$work/trust.p12" "$work/ready"
  rmdir "$work"
}
trap cleanup EXIT
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj '/CN=localhost' \
  -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' -keyout "$work/key.pem" -out "$work/cert.pem"
keytool -importcert -noprompt -alias test-endpoint -file "$work/cert.pem" -keystore "$work/trust.p12" -storepass changeit
ffmpeg -nostdin -loglevel error -y -listen 1 -i rtmp://127.0.0.1:19385/live/check -c copy build/rtmps-received.mkv &
listener=$!
python3 - "$work" <<'PYTLS' &
import pathlib, socket, ssl, sys, threading
work = pathlib.Path(sys.argv[1])
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(work / "cert.pem", work / "key.pem")
context.set_servername_callback(lambda sock, host, ctx: None if host == "localhost" else ssl.ALERT_DESCRIPTION_UNRECOGNIZED_NAME)
def pump(source, dest):
    try:
        while data := source.recv(65536):
            dest.sendall(data)
    except OSError:
        pass
    finally:
        try: dest.shutdown(socket.SHUT_WR)
        except OSError: pass
with socket.create_server(("127.0.0.1", 19386)) as listener:
    (work / "ready").touch()
    raw, _ = listener.accept()
    with context.wrap_socket(raw, server_side=True) as client, socket.create_connection(("127.0.0.1",19385)) as receiver:
        worker = threading.Thread(target=pump, args=(receiver,client), daemon=True)
        worker.start()
        pump(client,receiver)
        worker.join(5)
PYTLS
relay=$!
for _ in {1..60}; do [[ -f "$work/ready" ]] && break; sleep 0.1; done
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
