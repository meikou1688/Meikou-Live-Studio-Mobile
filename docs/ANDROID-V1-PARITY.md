# 第一版 Android 功能對照與驗收紀錄

檢查日期 2026-11-26。檢查對象為 Meikou 0.1.1／Git 8f57cb1。對照版本為 PRISM 官方 Android 更新頁所列 v5.4.0（2026-10-12），含手機端 PLUS 能力。現況判定為「未達首版發布條件」。本次為原始碼、官方文件及既有測試紀錄稽核，尚未以兩款 App 同機逐項操作。官方未列明的機型與平台差異，仍須用實機補查。

## 功能差距與驗收方式

| 功能 | 0.1.1 現況 | 第一版驗收 |
|---|---|---|
| 手機相機取景、外接收音、切換焦段 | 已接 Camera2、AudioRecord、編碼器；無 vivo 三鏡實測 | X300 Ultra 依序遮鏡，留存實體 ID、視角、換鏡停格與接收端音訊。以 INSTA360 MIC PRO 敲擊測試辨識實際收音路由 |
| 橫式與直式、自拍鏡像、觀眾畫面範圍 | Manifest 鎖橫式，預覽設為 16:9 | 測橫直開播、前後鏡頭鏡像、預覽與接收端構圖一致 |
| 專業相機 | 有變焦，缺手動對焦、曝光、ISO、白平衡與支援機種的防手震、構圖控制 | 調整時驗證 Camera2 回報與接收畫面，機型限制要呈現 |
| 拍照、錄影、直播存檔 | 有獨立 MP4；缺拍照，直播與錄影互斥 | 照片可開啟，直播可選同步保存或關閉存檔，核對錄影畫面、聲音與平台播出 |
| 畫質、編碼、網路 | 有候選解析度／FPS／位元率；HEVC 僅供錄影；重連及降碼率待測 | 驗證官方支援的 1440p、60 FPS 與 YouTube HEVC 各自適用條件；發送與接收分別檢查實際格式、丟格與同步 |
| 頻道登入、直播管理 | 僅能輸入一個 RTMP／RTMPS 網址 | 逐平台測登入、授權失效、排程、標題沿用、開播／結束、分享及錯誤；涵蓋 YouTube、Twitch、Facebook、SOOP、NAVER Shopping Live、Kick 等官方適用平台 |
| YouTube 進階操作 | 缺品牌帳號、續播、延遲選項、營利及開播前分享 | 用具資格的測試頻道分別驗證 API 回應及 YouTube 端實際狀態 |
| 多平台同步直播 | 單一目的地 | 對照 PLUS 同時最多六個頻道與平台例外，逐端留存可解碼影音，確認其中一路斷線時其他路仍可使用 |
| 我的工作室與場景 | 只有跨平台場景 JSON 範例，無執行中的合成器 | 攝影機、螢幕及適用模式依官方行為建立、保存、切換場景；圖層排序、鎖定、拖曳、縮放、旋轉後，在播出與錄影中逐一核對 |
| 素材、播放與資訊元件 | 無輸出端圖層 | 測台標、圖片、文字、跑馬燈、音樂、影片、圖／音／影片播放清單、網頁、QR Code、RTMP 輸入、日期、時鐘、比分板；達到官方 Android 模式適用的素材數量與組合 |
| 聊天與觀眾互動 | 缺平台聊天與播出元件 | 實測適用平台留言、淡出、Super Chat、YouTube Gifts、公告；核對手機畫面與觀眾端 |
| 即時字幕、濾鏡與特效 | 缺即時字幕、去背、美顏、動態及情緒效果 | 測真人說話字幕、色鍵、濾鏡、貼圖及動畫同時進入編碼畫面；素材與服務須具相應授權 |
| 音訊混音 | 有單一音訊輸入選擇；缺直播中靜音、混音台、降噪及變聲 | 分別測麥克風、藍牙、USB、背景音樂與支援擷取的系統音訊；逐路調整與靜音，驗證接收端 |
| 螢幕直播與背景播出 | 無 MediaProjection；Activity.onStop 會停止擷取 | 測橫直螢幕分享、指定分享範圍、遊戲聲、浮動控制、背景子母畫面、待機圖、相機與麥克風開關；確認授權及中止操作有效 |
| 虛擬主播 | 無 PNG／VRM 渲染與追蹤 | 測聲音驅動 PNG、3D 臉部追蹤、表情、姿勢、鏡頭運動、mocopi 與螢幕直播疊加 |
| 手機做為攝影機、手機遙控 | 僅有控制封包範例 | 測兩台裝置配對，手機輸出攝影機／螢幕／虛擬角色至電腦，以及手機遙控手機或電腦；記錄可互通的實際接收端 |
| AI 直播工具 | 缺語音轉寫、AI 助手、直播分析與精華短片 | 以一場真實測試直播產生可核對的轉寫、分析及可播放短片；標示語言、平台與外部服務條件 |
| 現場監測與故障處理 | 有網路類型與簡要提示，欠缺完整診斷及過熱防護證據 | 測直播狀態、平台中止、金鑰失效、過熱處置及長播。Speedify 須在兩條實際線路承載流量時拔除一路並檢查接收端 |
| Meikou 額外要求 | SRT、RootEncoder、OpenGL ES 尚未整合；主備援、雲端與 iOS 為架構草案 | SRT 等已要求的 Android 核心能力要用獨立接收端驗收。保留多機、主備援、雲端與 iOS 的擴充契約；手機現有對照功能一律納入首版 |

## 發布判定

第一個正式 Android 版本涵蓋官方 Android 手機版可用的功能，包括 PLUS 與依賴平台或雲端的使用者功能。Meikou 全功能開放，不設 Meikou 註冊、訂閱、點數或浮水印門檻；使用者操作自己的 YouTube 等第三方頻道時仍需該平台授權。商用素材與第三方服務採合法授權或獨立實作，須驗證使用者實際可完成相同工作。缺少平台核准、硬體、外部服務或實機證據時，保留缺項。開發過程可交付測試 APK；所有適用項目有通過紀錄後才可標記首版功能對等。

發布前以同一支手機、同一韌體與相同帳號權限，記錄 PRISM 版本、每個入口及進階設定，再以 Meikou 重跑。每項保留日期、版本、步驟、預期與實際結果、原始錄影或接收端紀錄。尚未實機發現的選項補入本表，不能以本表未列為理由省略。官方更新頁若出現新版，明列新增與移除項目再比對。

## 依據

- [Android 官方版本紀錄](https://guide.prismlive.com/mobile/feature-update/android-update)
- [官方說明索引](https://guide.prismlive.com/llms.txt)
- [手機版入門](https://guide.prismlive.com/mobile/guides/getting-started-with-prism-mobile)
- [PLUS 功能與頻道規格](https://guide.prismlive.com/prism-plus-and-credits/prism-plus)
- [各模式素材上限](https://guide.prismlive.com/mobile/guides/widgets-and-effects/others/source-usage-limit-guide)
- [螢幕直播](https://guide.prismlive.com/mobile/guides/features/screencast/using-screencast)
- [專業相機](https://guide.prismlive.com/mobile/guides/features/camera/using-camera-pro-mode)
- 本機依據包含 app/src/main/AndroidManifest.xml、Studio.kt、Capture.kt、Rtmp.kt、Viewfinder.kt、Lenses.kt、contracts/ 與 docs/TESTS.md。
