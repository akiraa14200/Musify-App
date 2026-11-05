# Strawhat Pirate yt-dlp backend

Small Python service used by the Android app for YouTube Music search and playback.

## Deploy on Render

1. Push this repository's `StreamGurd` branch.
2. In Render, create a new Blueprint and select this repository and the `StreamGurd` branch.
3. Render reads the root `render.yaml` file and creates `strawhat-pirate-music`.
4. After deployment, copy the service's HTTPS URL.
5. In Strawhat Pirate, open Music and tap **API** to save that URL.

Endpoints:
- `GET /health`
- `GET /api/search?q=...` returns JSON search results.
- `GET /api/stream?id=VIDEO_ID` resolves audio with yt-dlp and redirects to the temporary audio URL.

The service does not download or store music files. Direct stream URLs are cached in memory for 12 minutes.
