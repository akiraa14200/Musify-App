# Transcript Studio — Android Local Edition

A private, single-user transcription workspace designed to run locally on Android through Termux. The React interface runs in your phone browser; a Python API and SQLite database run on the same device.

**No Vercel, Supabase, Cloudflare, public deployment, account registration, or cloud database.**

## Features in this local build

- React + Vite responsive dark-glass interface.
- Local FastAPI backend bound to `127.0.0.1`.
- SQLite project/transcript/version database and local media files.
- Audio/video file upload and browser microphone recording.
- Groq Whisper Large V3 / Turbo transcription when a Groq key is configured.
- FFmpeg probing and splitting long files into 9-minute chunks.
- Persisted projects and transcript edits; failed jobs can be retried.
- Transcript editor, timestamped segments, version snapshots, TXT/SRT/VTT/JSON/CSV export.
- Optional Gemini text actions: cleanup, summary, chapters, keywords, and analysis.
- YouTube caption-only import attempt. It does not download media or bypass platform restrictions.
- Local storage status and delete-all-data control.

## Install on Android

1. Install Termux from a trusted source such as F-Droid or the official Termux GitHub releases. Do not mix Termux and add-on APKs from different sources.
2. Open Termux and run:

```sh
pkg update -y && pkg upgrade -y
pkg install -y git python nodejs ffmpeg
git clone https://github.com/akiraa14200/Musify-App.git
cd Musify-App
git checkout android-local-studio
pip install -r requirements.txt
npm install
cp .env.example .env
nano .env
```

3. Add your own keys to `.env` only if you want external AI providers:

```env
GROQ_API_KEY=your_groq_key
GEMINI_API_KEY=your_gemini_key
```

4. Build and start the app. Use two Termux sessions:

**Session 1 — API**

```sh
cd ~/Musify-App
uvicorn backend.main:app --host 127.0.0.1 --port 8000
```

**Session 2 — web interface**

```sh
cd ~/Musify-App
npm run dev
```

5. Open **http://127.0.0.1:5173** in Chrome on the same phone.

## Privacy and provider behavior

- Projects, transcripts, versions, and uploaded media are stored in the repository's ignored `data/` directory on your phone.
- Groq transcription sends the selected audio to Groq. Do not add a key if you do not want this.
- Gemini text tools send the transcript text to Google only when you explicitly click a tool.
- API keys stay in the local `.env` file and are never compiled into the frontend.
- YouTube import tries to retrieve accessible captions only. It does not download video/audio. Some videos have no accessible captions.
- The local API listens on loopback only; it is not available to other devices on your Wi-Fi.

## Current limitations — please read

This is the first Android-local conversion, not a claim that every item from the original wishlist is complete. PDF/DOCX export, full SRT/VTT subtitle editing, robust overlapping chunk boundary reconciliation, diarization, translation UI, chapter editing, advanced undo/redo, and complete offline ASR are not yet included. The current chunker splits at 9-minute boundaries and adjusts timestamps, but does not yet reconcile duplicated words across boundaries. YouTube captions depend on caption availability and upstream library compatibility. Gemini model access depends on the API key/account. Android may suspend Termux background work; keep Termux active during long transcriptions and retry a failed job if needed.

## Build checks

```sh
npm run typecheck
npm run build
python -m compileall backend
```
