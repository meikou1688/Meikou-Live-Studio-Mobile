# 開發進度｜2026-11-26

## 0.1.2 開發中

已接入正式擷取與編碼路徑的變更請見 CHANGELOG。螢幕模式現在擷取螢幕與麥克風，遊戲內部音訊混音仍待完成。影片圖層為靜音循環。多路傳送為自訂網址，尚無第三方平台排程及聊天整合。


## 0.1.1 介面更新

品牌來源為 meikou1688/meikou-live-studio 的 meikou/resources/logo 與 src/main.cpp；行動版沿用單一即時取景。

| 項目 | 狀態 |
|---|---|
| Android 工具鏈與獨立 Kotlin 專案 | 已找到本機工具，已離線編譯、封裝、簽署與驗章 |
| Camera2、AudioRecord、MediaCodec、MP4、同步時間戳記 | 已接入 APK，待手機錄影與拍板量測 |
| 廣角、一般、長焦 | 動態列舉、實體輸出指定、焦段快捷選擇、邏輯變焦已接入；vivo 實際公開的 ID 與可用組合待查 |
| RTMP、RTMPS | 已接入 APK；協定與桌面接收器獨立測試，網路與 TLS 尚待驗證 |
| 網路與音訊 | 顯示預設網路類型、音訊輸入、實際路由；位元率調整與重連待實網驗證 |
| RootEncoder、SRT、OpenGL ES 與素材圖層 | 尚未整合進 APK；RootEncoder 套件下載受限 |
| Speedify 與 INSTA360 MIC PRO | 須以實機、USB 接收器與雙行動網路實測 |
| 多機、遠端、雲端、主備援、iOS | 版本化格式草案，尚未啟用 |
| 正式品牌 | 應用程式圖示採用圓形徽章，控制區採用橫向標誌 |
| 備份 | tools/milestone.ps1 可執行建置、測試、含 SHA-256 的版本備份 |

首版範圍與逐項缺口請見 [Android 功能對照與驗收紀錄](ANDROID-V1-PARITY.md)。X300 Ultra 的單機直播、三鏡與收音為首要實測；所有尚缺的手機版對照功能均列入正式首版。

## Android 測試用戶端與 0.1.2 安裝檔

Google Auth Platform 已在專案 `meikou-live-studio-mobile` 建立 Android OAuth 用戶端，套件名稱為 `tw.meikou.live`，使用 0.1.2 測試 APK 的簽章。控制台顯示一位測試使用者。換用其他簽署金鑰時，需要登記對應的憑證指紋。個人帳號及授權權杖不收錄於儲存庫。

公開測試檔位於 `releases/v0.1.2/Meikou-Live-Mobile-0.1.2.apk`，SHA-256 為 `2df7cf6a08166ba7319532778634418f3589f57da9715c2fdbe292c322429de2`。此版本以自訂 RTMP 或 RTMPS 網址推流。App 內 YouTube 頻道授權、頻道讀取及 YouTube 接收端驗證仍未完成。

## 0.1.3 開發版

Android 版加入 Google Identity Services 授權流程，使用 youtube.readonly 讀取實際登入的 YouTube 頻道名稱。由使用者在待機時啟動。此步驟不會建立直播或變更頻道內容。需在對應簽章及已安裝 Google Play 服務的手機驗證授權、取消、網路錯誤及頻道名稱。公開的 0.1.2 APK 未包含本功能。

使用 Gradle 8.13、JDK 17 執行 gradle :app:assembleDebug。主要相依套件為 Google Maven 發布的 com.google.android.gms:play-services-auth:21.4.0。建置機的偵錯簽章與實機測試簽章不同時，需重新使用登記的金鑰簽署。GitHub Actions 產物供編譯驗證。

## 0.1.5 操作與設計

參考 PRISM Mobile 官方指南中素材工作區與純取景的分工，使用 Meikou 原生 Android 介面。手勢先對照實際合成畫面的圖層順序及旋轉範圍，只有在空白處起手才處理橫向切換。直播或錄影時保留停止控制。

參考來源：https://guide.prismlive.com/mobile/guides/getting-started-with-prism-mobile

鏡頭與素材觸控須在 VIVO X300 Ultra 實測，另需其他品牌手機確認 Camera2 實體鏡頭開放程度。


0.1.6 開發候選版將相機輸入尺寸與播出畫布分開。更換鏡頭會重新設定輸入緩衝區。背景直播須在前景明確勾選後開播，切換其他 App 可卸離預覽；離開工作且活動被銷毀會停止。預定直播採唯讀授權，透過 Android 分享選單傳送觀看連結。

SRT 主動連線現已接至 RootEncoder 2.8.2 的 srt 與 common 模組，接受裝置實際編碼的 H.264 或 H.265 與 AAC。SRT 目的地須提供連接埠及串流識別碼，目前不接受加密參數。測試在模擬器經 UDP 發給獨立 FFmpeg SRT 接收端。上游版本 https://github.com/pedroSG94/RootEncoder/tree/2.8.2 ，授權隨 APK 放在 assets/licenses。
