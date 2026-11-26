"""Build with Android SDK 36, Gradle 8.13, JDK 17 and Google Maven dependencies."""
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
java=jdk/"bin"/("java.exe" if os.name=="nt" else "java")
env=dict(os.environ,JAVA_HOME=str(jdk))
def run(*args):
    subprocess.run([str(a) for a in args],check=True,env=env)
def compile_kotlin(sources,jar,extra=""):
    cp=str(gradle/"lib/kotlin-stdlib-2.0.21.jar")+os.pathsep+str(android)+extra
    run(java,"-cp",str(gradle/"lib/*"),"org.jetbrains.kotlin.cli.jvm.K2JVMCompiler","-no-stdlib","-no-reflect","-jvm-target","1.8","-classpath",cp,"-d",jar,*sources)
def build():
    launcher=gradle/"bin"/("gradle.bat" if os.name=="nt" else "gradle")
    env["ANDROID_HOME"]=str(sdk)
    run(launcher,"-p",root,":app:assembleDebug","--console=plain")
    apk=out/"Meikou-Live-Mobile-0.1.3.apk"
    from sign_test_apk import sign_test_apk
    sign_test_apk(root/"app/build/outputs/apk/debug/app-debug.apk",apk)
    run(java,"-jar",bt/"lib/apksigner.jar","verify","--verbose",apk)
if __name__=="__main__":build()
