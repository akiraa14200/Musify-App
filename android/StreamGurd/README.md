# StreamGurd Android

Native Kotlin Android browser added to the Musify-App repository on the `StreamGurd` branch.

## Build locally
Open `android/StreamGurd` in Android Studio/AndroidIDE with JDK 17 and Android SDK Platform 35, then run:
```bash
gradle :app:assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Features
- Add and open HTTP/HTTPS website links in Android WebView.
- Domain-based aggressive blocking of known third-party ad and tracker hosts.
- Third-party cookies disabled, pop-up windows disabled, and mixed content blocked.
- Saved websites, recent history, fullscreen HTML5 video, and browser controls.
- Local preferences; no account, cloud backend, Vercel, or Supabase.

## Limitations
This is a domain blocklist, not a complete uBlock Origin/EasyList engine. It cannot reliably remove first-party ads or ads inserted directly into video streams. Blocking a site's required third-party services may affect playback. It does not bypass DRM, paywalls, subscriptions, or site access restrictions.