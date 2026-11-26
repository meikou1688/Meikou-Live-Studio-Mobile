"""Archive exactly the source, notices, docs and signed APK. Never package keys or credentials."""
from pathlib import Path
from datetime import datetime,timezone
import hashlib,json,zipfile
from build import root,out
def backup(destination):
    stamp=datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    destination.mkdir(parents=True,exist_ok=True)
    archive=destination/("Meikou-Live-Mobile-0.1.5-"+stamp+".zip")
    apk=out/"Meikou-Live-Mobile-0.1.5.apk"
    files=[(p,p.relative_to(root).as_posix()) for sub in ["app/src","tools","docs","contracts"] for p in (root/sub).rglob("*") if p.is_file() and "__pycache__" not in p.parts]
    files.extend((root/p,p) for p in ["gradle.properties",".github/workflows/android.yml","AGENTS.md","README.md","CHANGELOG.md","settings.gradle.kts","app/build.gradle.kts"])
    files.append((apk,apk.name))
    evidence=out/"transport-results.json"
    if evidence.exists():files.append((evidence,"evidence/transport-results.json"))
    with zipfile.ZipFile(archive,"x",zipfile.ZIP_DEFLATED) as z:
        hashes={}
        for path,name in files:
            data=path.read_bytes();z.writestr(name,data);hashes[name]=hashlib.sha256(data).hexdigest()
        z.writestr("SHA256.json",json.dumps(hashes,indent=2))
    with zipfile.ZipFile(archive) as z:
        for name,digest in json.loads(z.read("SHA256.json")).items():assert hashlib.sha256(z.read(name)).hexdigest()==digest
    return archive
if __name__=="__main__":
    import sys
    print(backup(Path(sys.argv[1]) if len(sys.argv)>1 else root/"backups"))


