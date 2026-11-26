param([string]$DeviceSerial, [int]$Seconds=30)
$ErrorActionPreference="Stop"
if ($Seconds -lt 10 -or $Seconds -gt 300){throw "測試時間請設定為 10 至 300 秒。"}
$root=Split-Path $PSScriptRoot -Parent
$androidRoot=if($env:ANDROID_HOME){$env:ANDROID_HOME}else{"$env:USERPROFILE/.codex/android-tools/sdk"}
$adb=Join-Path $androidRoot "platform-tools/adb.exe"
$argsDevice=@()
if($DeviceSerial){$argsDevice=@("-s",$DeviceSerial)}
& $adb @argsDevice get-state
if($LASTEXITCODE){throw "找不到已授權的 Android 手機。"}
$apk=Join-Path $root "build/Meikou-Live-Mobile-0.1.6.apk"
if(!(Test-Path $apk)){$apk=Join-Path $root "Meikou-Live-Mobile-0.1.6.apk"}
& $adb @argsDevice install -r $apk
if($LASTEXITCODE){throw "APK 安裝失敗。"}
New-Item -Type Directory -Force "$root/build" | Out-Null
& $adb @argsDevice shell dumpsys media.camera | Out-File -Encoding utf8 "$root/build/device-camera.txt"
$port=19385
$reverseMap=& $adb @argsDevice reverse --list
if($reverseMap -match "tcp:$port\s"){throw "測試連接埠已由其他作業使用。"}
& $adb @argsDevice reverse --no-rebind "tcp:$port" "tcp:$port"
if($LASTEXITCODE){throw "無法建立 USB 測試連線。"}
try {
    & $adb @argsDevice shell am start -n "tw.meikou.live/.Studio"
    $url="rtmp://127.0.0.1:$port/live/device"
    Write-Host "請於手機輸入 $url，選擇 H.264，再按開始直播。接收期間請輪流選擇廣角、一般及長焦，並拍手一次。"
    $output=Join-Path $root ("build/device-"+(Get-Date -Format yyyyMMdd-HHmmss)+".mkv")
    ffmpeg -hide_banner -n -listen 1 -i $url -t $Seconds -c copy $output
    if(!(Test-Path $output)){throw "接收端沒有取得影片。"}
    $json=ffprobe -v error -count_frames -show_streams -of json $output
    if($LASTEXITCODE){throw "無法分析接收檔。"}
    $tracks=($json | ConvertFrom-Json).streams
    if(!($tracks | Where-Object {$_.codec_name -eq "h264" -and [int]$_.nb_read_frames -gt 0}) -or !($tracks | Where-Object {$_.codec_name -eq "aac" -and [int]$_.nb_read_frames -gt 0})){throw "接收檔缺少有效影音軌。"}
    ffmpeg -v error -xerror -i $output -f null -
    if($LASTEXITCODE){throw "接收檔無法完整解碼。"}
    $json | Out-File -Encoding utf8 "$output.json"
    Write-Host "USB 推流已接收並解碼，請於手機停止直播。實際鏡頭與影音同步請檢查錄下的結果。"
}finally{& $adb @argsDevice reverse --remove "tcp:$port"}

