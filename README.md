# PrismBrowser

A native Android browser with a calm Safari-inspired interface and a floating glass-style toolbar.

## Current features
- Android WebView browsing engine
- Search/address bar with URL detection
- Back, forward, reload, and home controls
- Bookmarks saved locally
- Download support through Android DownloadManager
- Dark interface and responsive mobile layout
- GitHub Actions debug APK build

## Build
Open this project in Android Studio, or run:

```bash
gradle assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Project
- Language: Kotlin
- UI: Native Android views
- Minimum Android version: Android 8.0 (API 26)
- Compile SDK: 35

This is an early foundation. Tabs, private mode, history management, and advanced privacy controls are planned for later iterations.
