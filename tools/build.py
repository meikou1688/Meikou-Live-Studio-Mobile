"""Windows offline build with an installed Android SDK 36, Gradle 8.13 and JDK 17."""
from pathlib import Path
import os, subprocess, zipfile, shutil
root=Path(__file__).resolve().parents[1]
tool=Path(os.environ.get("MEIKOU_TOOLS",Path.home()/".codex/android-tools"))
jdk=Path(os.environ["JAVA_HOME"]) if "JAVA_HOME" in os.environ else next((tool/"jdk").glob("jdk-17*"))
sdk=Path(os.environ.get("ANDROID_HOME",tool/"sdk"))
gradle=Path(os.environ.get("GRADLE_HOME",tool/"gradle-8.13"))
android=sdk/"platforms/android-36/android.jar"
bt=sdk/"build-tools/35.0.0"
out=root/"build";out.mkdir(exist_ok=True)
java=jdk/"bin/java.exe"
env=dict(os.environ,JAVA_HOME=str(jdk))
def run(*args):
    subprocess.run([str(a) for a in args],check=True,env=env)
def compile_kotlin(sources,jar,extra=""):
    cp=str(gradle/"lib/kotlin-stdlib-2.0.21.jar")+os.pathsep+str(android)+extra
    run(java,"-cp",str(gradle/"lib/*"),"org.jetbrains.kotlin.cli.jvm.K2JVMCompiler","-no-stdlib","-no-reflect","-jvm-target","1.8","-classpath",cp,"-d",jar,*sources)
def build():
    main=root/"app/src/main"
    stage=out/"dex";stage.mkdir(exist_ok=True)
    jar=out/"mobile.jar"
    compile_kotlin(sorted((main/"java").rglob("*.kt")),jar)
    run(java,"-cp",bt/"lib/d8.jar","com.android.tools.r8.D8","--min-api","30","--lib",android,"--output",stage,jar,gradle/"lib/kotlin-stdlib-2.0.21.jar")
    package=out/"unsigned.apk"
    run(bt/"aapt.exe","package","-f","-M",main/"AndroidManifest.xml","-S",main/"res","-A",main/"assets","-I",android,"-F",package)
    with zipfile.ZipFile(package,"a",zipfile.ZIP_DEFLATED) as archive:
        for dex in stage.glob("classes*.dex"):archive.write(dex,dex.name)
    aligned=out/"aligned.apk";run(bt/"zipalign.exe","-f","4",package,aligned)
    key=Path(os.environ.get("MEIKOU_DEBUG_KEY",Path.home()/".android/debug.keystore"))
    if not key.exists():
        key=out/"debug.keystore"
        if not key.exists():run(jdk/"bin/keytool.exe","-genkeypair","-keystore",key,"-storepass","android","-keypass","android","-alias","androiddebugkey","-keyalg","RSA","-validity","10000","-dname","CN=Android Debug")
    apk=out/"Meikou-Live-Mobile-0.1.0.apk"
    run(java,"-jar",bt/"lib/apksigner.jar","sign","--ks",key,"--ks-key-alias","androiddebugkey","--ks-pass","pass:android","--out",apk,aligned)
    run(java,"-jar",bt/"lib/apksigner.jar","verify","--verbose",apk)
    run(bt/"aapt.exe","dump","badging",apk)
if __name__=="__main__":build()

