# Onks – Kiriwina United Church Dance and Drama (D&D)

Professional local WiFi walkie-talkie system for live stage productions.

## Quick Start in Android Studio

1. **Unzip** `Onks.zip` to a folder on your computer
2. Open **Android Studio** → **File → Open** → select the `Onks/` folder
3. Wait for Gradle sync to complete (requires internet to download dependencies)
4. Connect your Android phones via USB with **USB Debugging** enabled
5. Click **Run ▶** to install on each phone

## How to Use

- **Host phone**: plug in 3.5mm cable to mixer → tap "Host (Main Stage)"
- **Performer phones**: plug in TRRS mic → tap "Performer" → scan QR or enter PIN
- Press and hold the big PTT button (or Volume Up key) to talk
- Open `http://[host-ip]:8080` on any laptop for the web dashboard

## Requirements

- Android 10+ (API 29) on all phones
- Same WiFi network (or WiFi Direct auto-connects nearby)
- Wired 3.5mm or USB-C audio on Host phone

## Project Structure

```
app/src/main/java/com/kuc/onks/
├── MainActivity.kt          Role selector (Host / Performer)
├── HostActivity.kt          Host screen – mixer, QR, performer list
├── PerformerActivity.kt     Performer screen – PTT button
├── PerformerAdapter.kt      RecyclerView adapter for performer list
├── audio/AudioEngine.kt     48kHz Opus codec + AGC/compressor/gate
├── network/
│   ├── WifiP2PManager.kt    WiFi Direct P2P discovery & connection
│   └── UdpTransport.kt      UDP unicast + multicast audio transport
├── room/RoomManager.kt      PIN, QR, performer registry
├── server/WebDashboardServer.kt  HTTP server for laptop dashboard
├── service/OnksService.kt   Foreground service + wake lock
└── util/
    ├── AudioRouteHelper.kt  Forces wired audio output on Host
    └── QrHelper.kt          QR code generation
```

## Troubleshooting

| Problem | Fix |
|---|---|
| Gradle sync fails | Check internet; ensure Android SDK API 36 is installed |
| Build error: Concentus not found | Add `maven { url "https://jitpack.io" }` to settings.gradle repositories |
| No audio from mixer | Verify 3.5mm cable fully seated; check AudioManager routing |
| Performer can't connect | Ensure same WiFi; try entering PIN manually |
| Dashboard won't load | Confirm laptop on same WiFi; check IP shown on Host screen |

