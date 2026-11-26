"""Sign a local or CI build using the Android OAuth test client's registered key."""
import os
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory

from build import bt, java, out

EXPECTED_SHA1 = "1ebc327352a513ccd860b260b2f21cac2178e1a6"


def sign_test_apk(source, target):
    key = Path(os.environ.get("MEIKOU_DEBUG_KEY", Path.home() / ".android/debug.keystore"))
    if not key.exists() and "MEIKOU_DEBUG_KEY" not in os.environ:
        key = out / "debug.keystore"
    if not key.is_file():
        raise FileNotFoundError("The registered Android test keystore is required.")
    signer = [str(java), "-jar", str(bt / "lib/apksigner.jar")]
    with TemporaryDirectory(dir=target.parent) as temporary:
        signed = Path(temporary) / "signed.apk"
        subprocess.run(signer + ["sign", "--ks", str(key), "--ks-key-alias", "androiddebugkey",
                                 "--ks-pass", "pass:android", "--out", str(signed), str(source)], check=True)
        certs = subprocess.check_output(signer + ["verify", "--print-certs", str(signed)], text=True)
        if f"Signer #1 certificate SHA-1 digest: {EXPECTED_SHA1}" not in certs.splitlines():
            raise ValueError("The signed APK does not match the registered Android OAuth client.")
        signed.replace(target)


if __name__ == "__main__":
    sign_test_apk(Path(sys.argv[1]), Path(sys.argv[2]))
