"""Sign a local or CI build using the Android OAuth test client's registered key."""
import argparse
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory

from build import bt, java, out

EXPECTED_SHA1 = "1ebc327352a513ccd860b260b2f21cac2178e1a6"
BETA_SHA1 = "3b1d9eaaa214af09a0f8e220e509f110c4f69239"


def sign_test_apk(source, target, *, beta=False):
    if beta:
        directory = Path(os.environ.get("MEIKOU_BETA_SIGNING_DIR", Path.home() / ".local/share/meikou-live-signing"))
        key = directory / "mobile-beta.p12"
        password = "file:" + str(directory / "mobile-beta.pass")
        alias = "mobile-beta"
        fingerprint = BETA_SHA1
    else:
        key = Path(os.environ.get("MEIKOU_DEBUG_KEY", Path.home() / ".android/debug.keystore"))
        if not key.exists() and "MEIKOU_DEBUG_KEY" not in os.environ:
            key = out / "debug.keystore"
        password = "pass:android"
        alias = "androiddebugkey"
        fingerprint = EXPECTED_SHA1
    if not key.is_file():
        raise FileNotFoundError("The matching Android signing keystore is required.")
    aapt = bt / ("aapt.exe" if os.name == "nt" else "aapt")
    package = "tw.meikou.live.beta" if beta else "tw.meikou.live"
    manifest = subprocess.check_output([aapt, "dump", "badging", source], text=True)
    if not manifest.startswith(f"package: name='{package}' "):
        raise ValueError("The APK package does not match the selected Android signing identity.")
    signer = [str(java), "-jar", str(bt / "lib/apksigner.jar")]
    with TemporaryDirectory(dir=target.parent) as temporary:
        signed = Path(temporary) / "signed.apk"
        subprocess.run(signer + ["sign", "--ks", str(key), "--ks-key-alias", alias,
                                 "--ks-pass", password, "--out", str(signed), str(source)], check=True)
        certs = subprocess.check_output(signer + ["verify", "--print-certs", str(signed)], text=True)
        if f"Signer #1 certificate SHA-1 digest: {fingerprint}" not in certs.splitlines():
            raise ValueError("The signed APK does not match the expected Android signing identity.")
        signed.replace(target)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--beta", action="store_true")
    parser.add_argument("source", type=Path)
    parser.add_argument("target", type=Path)
    args = parser.parse_args()
    sign_test_apk(args.source, args.target, beta=args.beta)
