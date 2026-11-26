# Meikou Live Studio Mobile

**首版驗收尚未通過。** 0.1.6 為跨廠牌技術驗證版本。Windows USB 安裝請執行 `tools/install-android.cmd`，手機亦可直接開啟 APK。請參閱[首版 Android 功能對照與驗收紀錄](docs/ANDROID-V1-PARITY.md)。


適用於具備相機、支援 Android 11 以上的 Android 手機。鏡頭與影音支援程度依裝置公開能力而定。此次交付使用偵錯簽章。解壓版本備份即可取得 APK、完整原始碼與測試紀錄。

## 手機操作

1. 在手機開啟 APK，依 Android 提示完成安裝，允許相機及麥克風。橫拿手機，進入取景畫面。
2. 點「檢視手機硬體參數」，查看母相機、實體 ID、感光元件寬度、焦距、候選解析度、可用編碼器與音訊輸入。「複製參數」可帶回本機資料供後續比對。
3. 選「廣角」「一般」「長焦」或展開鏡頭清單。快捷鍵按系統回報焦距估算，只列出有資料的類別。實體項目將預覽與編碼輸出指定至該實體 ID。邏輯相機的變焦滑桿交由手機決定切換。作用中 ID 可能顯示未回報。
4. 先以 1280×720、30 FPS、4 Mbps、H.264 測試。連接 INSTA360 接收器後啟動 App，在音訊輸入選單選擇系統列出的裝置。以畫面上的實際收音路由及回放確認。變更裝置後重新開啟 App 可重讀清單。
5. 按「本機錄影」，切換鏡頭並拍手，停止後從顯示的檔案位置取出 MP4。成功匯出後可在手機相簿或 Movies/Meikou Live 找到錄影。匯出失敗會保留原始檔，備用資料夾為 Android/data/tw.meikou.live/files/Movies，若檔案管理員無法存取，可用 adb pull /sdcard/Android/data/tw.meikou.live/files/Movies。
6. 使用自己的測試接收端，分別填寫 RTMP／RTMPS 伺服器與串流金鑰，或填入 SRT 主動連線網址，再按「開始直播」。以接收端預覽確認影像、聲音及同步。首次驗收請使用私人測試串流。預設切到背景會停止。需要切換其他 App 時，請在開播前勾選「切換其他 App 時繼續相機直播」，並允許通知。可在通知中停止直播。手機端「已送出影音」只表示發送端狀態。

## 建置與重現

Windows 使用 Python 3、JDK 17、Gradle 8.13、Android SDK 36、Build Tools 35.0.0 與 Platform Tools。執行 tools/prepare.ps1 會檢查工具、在已設有 sdkmanager 的環境補齊 SDK 套件，再用 Kotlin 編譯、D8、aapt、zipalign、apksigner 建置。可指定 JAVA_HOME、GRADLE_HOME、ANDROID_HOME 或 MEIKOU_TOOLS。Gradle 專案也可供 Android Studio 匯入；此次實際驗證使用離線建置腳本。具備 FFmpeg 與 ffprobe 時，可執行 python tools/test_transport.py。重大版本執行 tools/milestone.ps1 自動建置、測試與備份。

目前實作及驗收狀態請見 docs/PROGRESS.md、docs/TESTS.md 與 docs/ARCHITECTURE.md。
