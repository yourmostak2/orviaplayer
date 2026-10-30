# Changelog

## v1.0.0
First public release.

### Added
- Material 3 player UI, light and dark theme follow the device
- Playlist with add videos, add folder, remove and clear
- Resume playback from where you left off
- Gestures: swipe to seek, brightness (left), volume (right), double-tap +-10 s, pinch to zoom
- Fullscreen and aspect ratio button (Fit, Fill, Stretch)
- Subtitles from your phone (.srt, .vtt, .ass) and auto-load of subtitles with the same name
- Audio track and playback speed (0.25x to 4x) menus
- Picture-in-Picture
- Hardware decoding first, FFmpeg fallback for more formats

### Known limits
- Not every codec or format is supported. If a file has no sound or no video, the app shows which codec is missing.
- Installed over a different signing key? Uninstall the old build once, then install this one.
