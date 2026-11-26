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
