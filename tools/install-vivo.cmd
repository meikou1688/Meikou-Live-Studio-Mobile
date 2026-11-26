@echo off
setlocal
chcp 65001 >nul
set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=%USERPROFILE%\.codex\android-tools\sdk\platform-tools\adb.exe"
set "APK=%~dp0..\releases\v0.1.4\Meikou-Live-Mobile-0.1.4.apk"
if not exist "%ADB%" goto error
if not exist "%APK%" goto error
echo 請解鎖 VIVO，若手機詢問是否允許 USB 偵錯，請點允許。
"%ADB%" -d get-state || goto error
for /f "delims=" %%M in ('""%ADB%" -d shell getprop ro.product.manufacturer"') do set "MAKER=%%M"
if /I not "%MAKER%"=="vivo" goto error
"%ADB%" -d install -r "%APK%" || goto error
"%ADB%" -d shell am start -n tw.meikou.live/.Studio || goto error
echo 已完成安裝。請在手機允許相機及麥克風權限。
pause
exit /b 0
:error
echo 安裝未完成。請確認只有一支 VIVO 以 USB 連線，且已允許偵錯，再重新執行。
pause
exit /b 1
