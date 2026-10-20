## AntiRecordingShield

**Anti-Recording / Anti-ASR**

Mask Sound for hidden recording device and AI recognition, 降低附近裝置錄音與 AI 語音辨識的有效性。

# Versions (last 1.1.4)

- 0.1
  - Kotlin / Android Studio project 專案
  - START / STOP (Shielding) button,  反錄音聲學遮蔽
  - Sound Mask strength adapted, 可調整遮蔽強度
  - Real-time 44.1 kHz output, 即時 AudioTrack
- 0.2, 低音量、可限制輸出的研究模式
  - 原始語音
  - 加入 masking 後的語音
  - 手機錄音結果
  - ASR transcription
  - WER/CER
  - 🎙️ AudioRecord 即時麥克風分析
  - 📊 1024-point FFT
  - detect 1-4 KHz frequency, 偵測 1–4 kHz 語音頻段
  - Adaptive Masking，不再只是固定噪音
  - Real time Display 即時顯示：
  - Speech-band %
  - Peak frequency
  - Masking %
  - AudioTrack 即時輸出
- 0.3
  - Monier whether hidden recording had,  即時麥克風監測
  - Spectrum/Sound Analysis, 頻譜/音量特徵分析
  - Confidence 0–100%
  - POSSIBLE RECORDING / ACOUSTIC ANOMALY
  - NOT DETECTED
  - Adaptive Masking,  保留原本 Adaptive Masking 架構
  - UI added

- 0.7
  Add some options
  - Speech-shaped noise, 物理隔離
  - Babble Simulation, 加上多人交談聲
  - UltraSound, 超音波干擾
  - Adversarial WAV, 對抗式擾動 

- 1.1
  Strength for devices scanning
  - Analyze Sound, 聲學分析、
  - Scan WIFI/Bluetooth devuces, Wi-Fi 掃描、Bluetooth LE 掃描, ** enable Wifi/Bluetooth** 
  - scanning data option, 資料管理
  - tag the known devices, 標記裝置標籤

 - 1.1.4
  - Wifi/BT Scan Radar  

 # Project, [src]
   - [AndroidStudio.md](AndroidStudio.md), use/install Cell phone to test
   - Apk, [AntiRecordingShield/src/app//build/outputs/apk/debug/app-debug.apk]

 # Test Platform
   - Manjaro, Android Studio, 
   - openjdk 26.0.2.1, gradle 9.4.1

 # Concerned, 
   - diffusion, cchuang2009_@_gmail.com 
   - chatgpt bot,
   - claude bot, 