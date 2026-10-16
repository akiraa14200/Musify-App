# 8 Audio

Native Android audio streaming player with a minimal red/black glass-inspired interface. Built with Kotlin, Jetpack Compose and AndroidX Media3 ExoPlayer.

## Features
- Play direct HTTP(S) audio stream URLs you are authorized to use.
- Pick local audio through Android's document picker.
- Background playback through MediaSessionService and Android media controls.
- Search saved tracks, favorites, queue controls and seek bar.
- Track list and favorites persist on-device.

This is a streaming player, not a licensed music catalogue. It does not scrape video platforms or bypass DRM. To ship a searchable catalogue, connect a backend that has distribution rights to the audio.

## Build
Open the `8-Audio` directory in Android Studio, use JDK 17 and Android SDK 35, sync Gradle, then build the debug variant. Requires an internet connection for Gradle dependencies.
