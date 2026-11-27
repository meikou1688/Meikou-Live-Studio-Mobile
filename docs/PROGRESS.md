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

加入日期、QR Code、比分板即時加減分；透過同一 OpenGL 畫布進入推流與錄影。螢幕分享新增可選的麥克風與內部音訊混音，來源 App 須允許 Android 擷取播放內容。此項與實體裝置收音仍待驗收。

音訊混音已可分別調整麥克風與螢幕內部音訊的音量，直播中返回 App 仍可調整及靜音。主機 PCM 測試涵蓋獨立音量、混音與峰值裁切；INSTA360 MIC PRO、實際節目內部音訊、藍牙及不同廠牌手機仍須分開驗收。

YouTube 可在手機選擇標題、當地日期時間、隱私與延遲；使用者按下建立後，要求管理權限，建立預定直播、專屬串流並綁定，RTMPS 金鑰僅置於記憶體。平台自動開播與自動結束關閉。尚未在真實頻道建立活動驗收。

2026-11-27：六路 RTMP、獨立 SRT、兩次 MP4 與橫式 QR 拖曳縮放已在 Android 15 模擬器通過編碼、接收及解碼。原始量測見 docs/evidence/camera-receiver-45867400228.json。依需求暫緩封裝，所有功能差距及實機驗收通過後再發布。

下一輪開發加入 YouTube 直播聊天室唯讀檢視，依服務回傳間隔更新留言，關閉即停止讀取；頻道權限及觀眾實際留言仍待授權實測。新增圖片定時輪播與靜音影片循環播放清單，透過系統檔案選擇器匯入，可與其他素材一起拖曳縮放，並納入相同的影音合成核心。

加入場景背景音樂清單，解碼為 PCM、聲道混合、重採樣到 48 kHz 後與麥克風混入 AAC，可在場景內調音量或靜音。相機模式的主要 RTMP 路徑斷線時保留錄影與其他輸出，重連後補送編碼參數與關鍵影格。

## Android 0.1.9 測試版的簽署與頻道授權

此次交付使用獨立套件 `tw.meikou.live.beta` 及固定 Beta 憑證。Google Auth Platform 的 Android 用戶端須登記這個套件與 SHA-1 `3B:1D:9E:AA:A2:14:AF:09:A0:F8:E2:20:E5:09:F1:10:C4:F6:92:39`。實際帳號完成 Google 同意並讀取到頻道，才可認定帳號驗收通過。

驗證建置產物後，在持有 Beta 簽署憑證的環境執行 `python tools/sign_test_apk.py --beta app-preview.apk Meikou-Live-Mobile-beta.apk`。可透過 `MEIKOU_BETA_SIGNING_DIR` 指定安全的憑證目錄。工具會核對 APK 套件名稱與簽署憑證指紋。CI 的臨時簽章只供模擬器測試。0.1.8 場景保存在原 App，新安裝的 Beta 使用獨立場景。
