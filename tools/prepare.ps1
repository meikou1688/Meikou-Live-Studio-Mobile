$ErrorActionPreference = "Stop"
$toolsRoot = if ($env:MEIKOU_TOOLS) { $env:MEIKOU_TOOLS } else { Join-Path $env:USERPROFILE ".codex/android-tools" }
$env:JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { (Get-ChildItem "$toolsRoot/jdk" -Directory -Filter "jdk-17*" | Select-Object -First 1).FullName }
$env:ANDROID_HOME = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $toolsRoot "sdk" }
$env:GRADLE_HOME = if ($env:GRADLE_HOME) { $env:GRADLE_HOME } else { Join-Path $toolsRoot "gradle-8.13" }
foreach ($path in @("$env:JAVA_HOME/bin/java.exe", "$env:GRADLE_HOME/lib/kotlin-compiler-embeddable-2.0.21.jar")) {
    if (!(Test-Path -LiteralPath $path)) { throw "找不到建置工具 $path。請安裝 JDK 17、Gradle 8.13，或指定對應的環境變數。" }
}
if (!(Test-Path "$env:ANDROID_HOME/platforms/android-36/android.jar") -or !(Test-Path "$env:ANDROID_HOME/build-tools/35.0.0/aapt.exe") -or !(Test-Path "$env:ANDROID_HOME/platform-tools/adb.exe")) {
    $manager = Get-ChildItem "$env:ANDROID_HOME/cmdline-tools" -Recurse -Filter sdkmanager.bat | Select-Object -First 1
    if (!$manager) { throw "缺少 Android SDK Command-line Tools。" }
    & $manager.FullName "--sdk_root=$env:ANDROID_HOME" "platform-tools" "platforms;android-36" "build-tools;35.0.0"
    if ($LASTEXITCODE) { throw "Android SDK 套件安裝失敗。" }
}
python "$PSScriptRoot/build.py"
exit $LASTEXITCODE
