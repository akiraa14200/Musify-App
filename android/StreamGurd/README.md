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
- Domain-based blocking of known third-party ad and tracker hosts.
- Third-party cookies disabled for normal browser tabs, pop-up windows disabled, and mixed content blocked.
- Saved websites, recent history, fullscreen HTML5 video, and browser controls.
- Local preferences; no account, cloud backend, Vercel, or Supabase.
- Music search uses the official YouTube Data API v3 with a user-provided API key stored locally on the device.
- Music playback uses YouTube's official embedded player inside Android WebView. YouTube controls, ads, and playback restrictions remain available; the app does not extract audio streams or use yt-dlp.

## YouTube Music setup
1. Create a Google Cloud project and enable YouTube Data API v3.
2. Create an API key and restrict it appropriately in Google Cloud.
3. Open Music Player in the app, tap **API KEY**, and save the key on the device.
4. Search for a video and select a result. Playback loads inside YouTube's embedded player.

## Limitations
This is a domain blocklist, not a complete uBlock Origin/EasyList engine. It cannot reliably remove first-party ads or ads inserted directly into video streams. Blocking a site's required third-party services may affect playback. It does not bypass DRM, paywalls, subscriptions, or site access restrictions. Some YouTube videos may disallow embedding; those videos must be opened on YouTube instead.
