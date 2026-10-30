<div align="center">

# ORVIA player

**A clean, minimal Material 3 video player for Android.**
Plays your local videos in original quality. No login, no ads, no tracking.

[**Download the latest APK**](../../releases/latest)

</div>

## Features
- **Original quality:** videos are never re-encoded or downscaled.
- **Wide format support:** hardware decoding first, FFmpeg decoders as fallback for extra formats (MKV, AVI, AC3, DTS and more). Not every codec is guaranteed to work.
- **Material 3 design:** minimal controls, follows your phone's light or dark theme.
- **Playlist:** add videos or a whole folder, remove items, resume where you stopped.
- **Gestures:**
  - Swipe left or right to seek
  - Swipe up or down on the left side for brightness
  - Swipe up or down on the right side for volume
  - Double-tap the left or right side to jump 10 seconds
  - Pinch to zoom in and out
- **Fullscreen** and **aspect ratio** button (Fit, Fill, Stretch)
- **Subtitles:** add .srt, .vtt or .ass files from your phone
- **Audio track** switching, playback speed (0.25x to 4x)
- **Picture-in-Picture**
- No login, no ads, no analytics

## Install
1. Open the [Releases](../../releases/latest) page on your phone.
2. Download `ORVIA-player-*.apk`.
3. Open it and allow **Install unknown apps** if asked.
4. If Play Protect warns you, choose **Install anyway** (the app is not from the Play Store).

Requires Android 8.0 (API 26) or newer.

## Build from source
Android Studio: open the project and run **Build > Build APK(s)**.
This repository also builds automatically with GitHub Actions. Pushing a tag like `v1.0.0` publishes a release.

## Privacy
ORVIA player works only with files you choose. Nothing is uploaded. See [PRIVACY.md](PRIVACY.md).

## License
MIT. See [LICENSE](LICENSE).
