## AntiRecordingShield

**Anti-Recording / Anti-ASR：降低附近裝置錄音與 AI 語音辨識的有效性。**

# versions
- 0.1
  - Kotlin / Android Studio 專案
  - START / STOP 反錄音聲學遮蔽
  - 可調整遮蔽強度
  - 44.1 kHz 即時 AudioTrack
- 0.2, 低音量、可限制輸出的研究模式
  - 原始語音
  - 加入 masking 後的語音
  - 手機錄音結果
  - ASR transcription
  - WER/CER
  - 🎙️ AudioRecord 即時麥克風分析
  - 📊 1024-point FFT
  - 偵測 1–4 kHz 語音頻段
  - Adaptive Masking，不再只是固定噪音
  - 即時顯示：
  - Speech-band %
  - Peak frequency
  - Masking %
  - AudioTrack 即時輸出
- 0.3
  -   即時麥克風監測
  - 頻譜/音量特徵分析
  - Confidence 0–100%
  - POSSIBLE RECORDING / ACOUSTIC ANOMALY
  - NOT DETECTED
  - 保留原本 Adaptive Masking 架構

 # Project, [src]
   - Apk, [AntiRecordingShield/src/app//build/outputs/apk/debug/app-debug.apk]

 # Test Platform
   - Manjaro, Android Studio, 
   - openjdk 26.0.2.1, gradle 9.4.1

 # Concermed, 
   diffusion, cchuang2009_@_gmail.com 