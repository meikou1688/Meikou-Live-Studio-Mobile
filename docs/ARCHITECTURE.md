# 技術架構

## 0.1.2 實作

Camera2 或 MediaProjection 寫入 SurfaceTexture；SceneCompositor 以 OpenGL ES 2.0 同步畫到預覽及編碼器 Surface。螢幕模式使用獨立前景服務及系統通知停止控制。Capture 把編碼資料分別送入 MediaMuxer、主要 Rtmp 及各額外輸出的獨立佇列。網路中斷時已完整封裝的前段錄影仍可匯出；重連另開檔案。額外路徑故障會停止該路；主要路徑故障會重啟整場傳送。


Studio 負責使用者操作、權限、生命週期及狀態。Viewfinder 先建立純預覽工作階段，關閉完成後交付相機給 Capture。Capture 透過 Camera2 將畫面分別送往 SurfaceView 與 MediaCodec 輸入 Surface，透過 AudioRecord 將 PCM 送入 AAC-LC 編碼器。音訊採 48 kHz 單聲道，時間戳記以取樣數推算。影片首幀對齊單調時鐘，切鏡沿用編碼器，實際同步誤差須以實機拍板量測。

Lenses 讀取邏輯與實體相機。估計等效焦距使用實際焦距乘以 36 除以感光元件寬度，作為橫向視角近似值。快捷分類門檻為 22 與 50 mm，僅為操作分類。指定實體鏡頭時，兩個 OutputConfiguration 均設定 physicalCameraId。邏輯相機則使用 CONTROL_ZOOM_RATIO，實際作用中鏡頭可由擷取結果讀取。切換實體鏡頭會重建相機工作階段，可能短暫停格。解析度、影格率及雙輸出組合仍受韌體限制。

Rtmp 包含握手、AMF0 發布、串流 ID、區塊分段、流量確認及 ping 回應。Avc 封裝 SPS/PPS 與 AVC NAL，AAC 配置與取樣分別送出。RTMPS 使用系統信任的 TLS 連線及主機名稱驗證。以寫入阻塞時間微調編碼器位元率，範圍為設定值的 25% 至 100%；此判斷仍須實網校準。斷線最多三次重連，分別等待 2、4、8 秒。錄影採 MediaMuxer，取得兩軌格式後啟動。

## 擴充界面

在相機與編碼器之間導入 OpenGL ES 合成器時，場景以 contracts/scene-v1.example.json 表示。座標採中心點，寬高及座標相對畫布，旋轉採順時針角度。圖層陣列由底至頂。圖片、文字、影片及跑馬燈將共用此格式。RootEncoder/SRT 須以固定版本轉接至實際接收端驗收，避免綁死攝影機選擇。未來多機及主備援分別記錄來源 ID、輸出 ID 與接收端健康狀態。遠端控制以具 requestId 的版本化命令回報結果，雲端接收端另設部署專案。兩個 JSON 檔目前是跨平台介面草案。

## 套件

APK 使用 Android 原生 API 與 Kotlin 標準函式庫 2.0.21。Kotlin 採 Apache License 2.0。FFmpeg 僅作電腦端測試工具。RootEncoder 上游採 Apache License 2.0，本版 APK 未內含該套件。桌面版徽章與標誌由使用者已有的原圖縮製。

https://github.com/JetBrains/kotlin
https://github.com/pedroSG94/RootEncoder
https://developer.android.com/media/camera/camera2/multi-camera
https://developer.android.com/reference/android/media/MediaCodec


相機輸入尺寸與編碼解析度分開：Camera2 逐鏡頭列舉 SurfaceTexture 尺寸與影格最短時間，OpenGL ES 等比縮放至播出畫布。背景相機直播由具 camera 與 microphone 類型的前景服務提供通知；畫面卸離只移除 EGL 預覽視窗，保留編碼視窗。

音訊以優先序執行緒讀取 PCM 並保留時間戳。合成器與麥克風共用單調時鐘。錄影及傳輸從有效關鍵影格開始，依影音兩軌都到達的時間交錯送出。同一批 Android 編碼資料可傳至 RTMP／RTMPS，或透過 RootEncoder SRT 模組傳送 MPEG-TS。
