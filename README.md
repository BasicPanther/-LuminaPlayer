# Lumina Player

**Lumina Player** is a modern, high-performance local media player for Android designed with a sleek, minimalist dark aesthetic. Built strictly with Kotlin, Jetpack Compose (Material 3), and AndroidX Media3 ExoPlayer, Lumina Player brings a smooth desktop/mobile viewing experience to your movies, TV shows, and local video library.

---

## Features

- **Smart Local Library Scanner**: Automatically scans device folders, parses titles, episode info, and organizes media into Movies and TV Shows.
- **Advanced Subtitle Management**:
  - **Embedded Track Auto-Detection**: Seamlessly detects and lists embedded container sub-tracks (SRT, ASS, VTT, PGS, VobSub).
  - **Sidecar File Scanning**: Auto-discovers local `.srt`, `.vtt`, `.ass`, `.ssa`, and `.sub` files placed alongside your video files.
  - **Local Subtitle File Attachment**: Easily pick and attach custom subtitle files from internal storage or SD cards.
  - **Easy Subtitle Removal**: Instantly remove attached local subtitle tracks with a single tap directly inside the player UI.
  - **Online Subtitle Downloader**: Automatically fetch matching subtitles online via TMDb / OMDb integrations.
  - **AI Offline CC Generation**: Transcribe media on-demand using Gemini AI models.
  - **Custom Subtitle Styling**: Adjust font sizes, background colors, opacities, and typography styles on the fly.
- **Gesture Controls & Playback Controls**:
  - Swipe left/right for fast seeking with high-precision thumbnail previews.
  - Vertical swipe gestures for brightness and volume control.
  - Playback speed selection ($0.25\times$ to $2.0\times$), aspect ratio switching (Fit, Crop, Stretch), and background audio mode.
- **Metadata Enrichment**: Automatically fetches poster art, backdrops, plot synopses, release dates, genres, and cast details using OMDb / TMDb APIs.
- **Cloud Sync & Remote Streaming**: Connect to WebDAV / remote cloud storage sources to stream videos remotely and sync watch progress.
- **Minimalist Material 3 UI**: Clean dark canvas with dynamic accent colors, smooth entry transitions, and edge-to-edge support.

---

## Application Screenshots

Below is an overview of the core interfaces in **Lumina Player**:

```
+------------------------------------+  +------------------------------------+
|  Lumina Player - Dashboard         |  |  Lumina Player - Media Player      |
+------------------------------------+  +------------------------------------+
| [ Search Movies & Shows... ]       |  | +--------------------------------+ |
|                                    |  | |                                | |
| Continue Watching                  |  | |         [ VIDEO CANVAS ]       | |
|  [Poster 1]  [Poster 2]  [Poster 3]|  | |                                | |
|   Movie A     Show S1E2   Movie B  |  | +--------------------------------+ |
|                                    |  | Subtitle Tracks                  | |
| Library Folders                    |  |  • Track 1 - [English - SRT]     | |
|    /sdcard/Movies  (12 items)      |  |  • Local File: custom_sub.srt    | |
|    /sdcard/TV      (8 items)       |  |    [ Remove Attached Subtitle ]   | |
|                                    |  | Player Controls                  | |
| Settings & API Keys                |  |  [ << 10s ]   [ PLAY ]   [ 10s >> ]| |
+------------------------------------+  +------------------------------------+
```

---

## Architecture & Tech Stack

- **UI Framework**: [Jetpack Compose](https://developer.android.com/jetpack/compose) with Material Design 3
- **Media Engine**: [AndroidX Media3 ExoPlayer](https://developer.android.com/guide/topics/media/media3)
- **Local Database**: [Room Database](https://developer.android.com/training/data-storage/room) with KSP
- **Networking**: [Retrofit](https://square.github.io/retrofit/) & [Moshi](https://github.com/square/moshi)
- **Async & Reactive Flow**: Kotlin Coroutines & `StateFlow` / `SharedFlow`
- **Image Loading**: [Coil Compose](https://coil-kt.github.io/coil/)
- **Secret Management**: Android Secrets Gradle Plugin via `.env`

---

## Getting Started

### Prerequisites

- **Android Studio**: Ladybug (2024.2.1) or newer
- **JDK**: Version 11 or 17
- **Android SDK**: Minimum SDK 24 (Android 7.0), Target SDK 36 (Android 15)

### Installation & Build Instructions

1. **Clone the Repository**:
   ```bash
   git clone https://github.com/your-username/LuminaPlayer.git
   cd LuminaPlayer
   ```

2. **Configure Environment Secrets (Optional)**:
   Lumina Player uses `.env` to supply optional API keys for online metadata fetching and AI transcription.
   
   Copy `.env.example` to `.env`:
   ```bash
   cp .env.example .env
   ```

   Open `.env` and fill in your keys (if desired):
   ```env
   # GEMINI_API_KEY: Used for AI offline subtitle/caption transcription
   GEMINI_API_KEY=your_gemini_api_key_here

   # OMDB_API_KEY: Used for movie & show metadata lookup
   OMDB_API_KEY=your_omdb_api_key_here
   ```
   > **Note**: If left blank or untouched, Lumina Player will run in pure local media mode without external API dependencies. You can also enter API keys at any time directly inside the in-app **Settings** menu.

3. **Build the APK**:
   ```bash
   ./gradlew assembleDebug
   ```

4. **Install on Device**:
   Connect your Android device via USB with ADB debugging enabled, or launch an emulator, then run:
   ```bash
   ./gradlew installDebug
   ```

---

## How to Use

### 1. Scanning Local Video Files
- On first launch, grant storage / media permissions.
- Tap **Scan Device Storage** or select custom directory folders in the **Library** tab.
- Lumina Player will populate your movies and series automatically with poster artwork and episode ordering.

### 2. Managing Subtitles
- **Select Subtitle Tracks**: Tap the **CC / Subtitle** button on the player overlay to view all detected embedded tracks and external files.
- **Attach a Local Subtitle**: Tap **Attach Local File** to browse your device for `.srt`, `.vtt`, `.ass`, or `.ssa` files.
- **Remove an Attached Subtitle**: Open the subtitle menu in the player overlay and tap **Remove Attached Subtitle File** (or tap the trash icon next to the active local track source) to clear it.
- **Adjust Appearance**: Customize subtitle text size, background color, font family, and vertical positioning under player settings.

### 3. Gesture Controls
- **Brightness**: Vertical swipe on the left side of the screen.
- **Volume**: Vertical swipe on the right side of the screen.
- **Seek**: Horizontal swipe anywhere on the video area.

---

## Security & Privacy

- **No Hardcoded Keys**: No API credentials or secrets are checked into source control.
- **Local-First**: All metadata, playback history, and configuration states are stored locally on device using an encrypted SQLite database via Room.

---

## License

```text 
Copyright 2026 Lumina Player Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
either express or implied. See the License for the specific language governing
permissions and limitations under the License.
```
