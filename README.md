# 👁️ MyEyes — AI-Powered Assistive Glasses for the Visually Impaired

> **🏆 3rd Place — Capstone Competition, Winter 2026**

MyEyes is a wearable assistive system that helps visually impaired individuals navigate their surroundings using real-time AI-powered object detection, OCR text reading, and audio feedback. The system pairs ESP32-S3 smart glasses with an Android companion app to deliver spoken alerts about nearby vehicles, crosswalks, pedestrian signals, street signs, and currency.

---

## 🎯 Key Features

| Feature | How It Works |
|---|---|
| **🚗 Vehicle Detection** | YOLOv8 detects cars, trucks, and buses — alerts *"Careful, car ahead"* |
| **🚦 Crosswalk Safety** | Combines crosswalk detection with pedestrian light color analysis — *"Green pedestrian light, safe to cross"* or *"Red pedestrian light, NOT safe to cross"* |
| **🛑 Stop Signs** | YOLOv8 detects stop signs — alerts *"Stop sign ahead"* |
| **💵 Currency Recognition** | Detects and verifies Canadian banknotes ($5–$100) using YOLO + PaddleOCR — *"Verified 20 Canadian dollars"* |
| **📝 Text Reading (OCR)** | Reads important text, signs, menus, and labels aloud using on-device PaddleOCR |
| **🔊 Audio Routing** | TTS audio is synthesized on the phone and streamed as WAV to the ESP32's I2S speakers |

---

## 🏗️ System Architecture

```
┌─────────────────────────┐        ┌─────────────────────────────────┐
│   ESP32-S3 Glasses      │        │   Android Companion App         │
│                         │        │                                 │
│  • OV2640 Camera        │──WiFi──│  • YOLOv8n (TFLite)             │
│  • MAX98357A Speakers   │  AP    │  • PaddleOCR (on-device)        │
│  • MJPEG stream @80     │◄──────│  • Text-to-Speech               │
│  • Audio receive @81    │        │  • Detection Announcer          │
│  • WiFi SoftAP          │        │  • CameraX (live camera mode)   │
└─────────────────────────┘        └─────────────────────────────────┘
```

The ESP32 creates a WiFi access point (`MyEyes`). The Android phone connects and receives an MJPEG camera stream over HTTP. Inference runs entirely on-device — no cloud services required.

---

## 📁 Project Structure

```
Capstone/
├── antieyes/                  # Android companion app (Kotlin)
│   ├── app/src/main/
│   │   ├── java/.../detector/
│   │   │   ├── MainActivity.kt          # Main activity, ESP32/camera sources
│   │   │   ├── YoloTfliteDetector.kt    # YOLOv8 TFLite inference
│   │   │   ├── DetectionAnnouncer.kt    # TTS announcements + crosswalk logic
│   │   │   ├── PaddleOcrEngine.kt       # On-device OCR
│   │   │   ├── MjpegStreamReader.kt     # ESP32 MJPEG stream parser
│   │   │   ├── Esp32AudioSender.kt      # WAV audio upload to ESP32
│   │   │   ├── CameraFrameSource.kt     # CameraX frame provider
│   │   │   ├── Detection.kt             # Detection data class
│   │   │   ├── NmsUtil.kt               # Non-max suppression
│   │   │   ├── LetterboxUtil.kt         # Image preprocessing
│   │   │   ├── OverlayView.kt           # Detection bounding box overlay
│   │   │   └── WordFilter.kt            # Dictionary-based OCR filtering
│   │   └── assets/
│   │       ├── newmodel100.tflite       # YOLOv8n model (not in repo)
│   │       ├── models/ocr/             # PaddleOCR models (not in repo)
│   │       ├── labels.txt              # Detection class labels
│   │       └── wordlist.txt            # Dictionary for OCR filtering
│   └── build.gradle
│
├── MyEyes_Audio_Test/         # ESP32-S3 firmware (PlatformIO / Arduino)
│   ├── src/main.cpp           # Camera stream, WiFi AP, I2S audio, web server
│   └── platformio.ini         # Board config (Freenove ESP32-S3 WROOM)
│
└── Poster/                    # Competition poster PDFs
```

---

## ⚙️ Tech Stack

| Layer | Technology |
|---|---|
| **Hardware** | Freenove ESP32-S3 WROOM, OV2640 Camera, MAX98357A I2S Amplifier |
| **Firmware** | Arduino framework via PlatformIO (C++) |
| **Android App** | Kotlin, Jetpack (CameraX, AppCompat), Coroutines |
| **Object Detection** | YOLOv8n → TensorFlow Lite (custom-trained, 12 classes) |
| **OCR** | PaddleOCR4Android (on-device, no cloud) |
| **Audio** | Android TTS → WAV synthesis → HTTP upload → I2S playback |
| **Communication** | WiFi SoftAP, MJPEG over HTTP, raw TCP audio |

---

## 🏷️ Detection Classes

The custom YOLOv8n model is trained to detect the following 12 classes:

| # | Class | Action |
|---|---|---|
| 0 | `100CanadianDollar` | OCR verify → *"Verified 100 Canadian dollars"* |
| 1 | `10CanadianDollar` | OCR verify → *"Verified 10 Canadian dollars"* |
| 2 | `20CanadianDollar` | OCR verify → *"Verified 20 Canadian dollars"* |
| 3 | `50CanadianDollar` | OCR verify → *"Verified 50 Canadian dollars"* |
| 4 | `5CanadianDollar` | OCR verify → *"Verified 5 Canadian dollars"* |
| 5 | `Crosswalks` | Crosswalk safety logic with traffic lights |
| 6 | `Important Text` | PaddleOCR → read aloud |
| 7 | `car` | *"Careful, car ahead"* |
| 8 | `green pedestrian light` | *"Green pedestrian light, safe to cross"* |
| 9 | `pedestrian light` | *"Pedestrian light ahead"* |
| 10 | `red pedestrian light` | *"Red pedestrian light, NOT safe to cross"* |
| 11 | `stop` | *"Stop sign ahead"* |

---

## 🚀 Getting Started

### Prerequisites

- **Android Studio** (Arctic Fox or later) with SDK 35
- **PlatformIO** (VS Code extension or CLI) for ESP32 firmware
- **Freenove ESP32-S3 WROOM** board with OV2640 camera module
- **MAX98357A** I2S amplifier + speaker

### Model Files (Not Included in Repo)

The following model files are excluded from the repository due to their size. You must obtain or train them separately:

| File | Location | Description |
|---|---|---|
| `newmodel100.tflite` | `antieyes/app/src/main/assets/` | Custom YOLOv8n model (~44 MB) |
| `cls.nb`, `det.nb`, `rec.nb` | `antieyes/app/src/main/assets/models/ocr/` | PaddleOCR models (~16 MB total) |

### Building the Android App

1. Open the `antieyes/` directory in Android Studio
2. Place your model files in the `assets/` directory (see above)
3. Sync Gradle and build the project
4. Deploy to an Android device (min SDK 24)

### Flashing the ESP32

1. Open `MyEyes_Audio_Test/` in VS Code with PlatformIO
2. Connect your Freenove ESP32-S3 via USB
3. Build and upload: `pio run --target upload`
4. The ESP32 will create a WiFi AP named **MyEyes** (password: `myeyes123`)

### Running the System

1. Power on the ESP32 glasses — listen for the startup chime
2. On your Android phone, connect to the **MyEyes** WiFi network
3. Open the MyEyes app and enter the stream URL: `http://192.168.4.1/stream`
4. Tap **Connect** — the camera feed and detections will begin

---

## 👥 Team

**Group 25** — Winter 2026 Capstone Project

---

## 📄 License

This project was developed as part of an academic capstone course. Please contact the team for licensing inquiries.
