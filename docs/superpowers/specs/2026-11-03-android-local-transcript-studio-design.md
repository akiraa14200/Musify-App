# Android Local Transcript Studio — Design

**Date:** 2026-11-03  
**Repository:** `akiraa14200/Musify-App`  
**Target branch:** `android-local-studio`

## Intent

Turn Transcript Studio into a private, single-user transcription and transcript-editing workspace that runs on the user's Android phone through Termux. The user opens a local address in their mobile browser. The app should not require Vercel, Supabase, Cloudflare, public hosting, account registration, or a paid subscription to run its local features.

## What exists today

The repository currently contains a React 19 + Vite + TypeScript interface and a Cloudflare Worker backend. The Worker depends on D1, R2, Queues, Worker secrets, and email/password sessions. The UI also requires sign-in, and the current upload route caps media at 20 MB. The README documents Cloudflare deployment. This architecture does not meet the approved Android-only/local-only direction.

The current React visual language is a dark, glass-style transcript workspace. Keep the useful design direction, but remove account screens and Cloudflare-specific UI. Replace the backend and setup instructions.

## Goals

1. Run locally in Termux on Android, with the server bound to loopback by default.
2. Store project records, transcript versions, segments, settings, and job state in a local SQLite database; store uploaded media and exports in a local app data directory.
3. Support audio/video file uploads and in-browser microphone recording.
4. Support YouTube URLs only where captions are legitimately available through supported/public caption access. Do not download YouTube media, bypass DRM, or bypass access controls. Explain clearly when captions are unavailable.
5. Use Groq Whisper (Large V3 / Turbo) as an optional transcription provider. Keep the API key on the local server, never in browser code. Clearly disclose that selected audio is sent to Groq.
6. Offer optional Gemini text tools for cleanup, summaries, translation, chapters, keywords, and content analysis. Send transcript text only unless the user explicitly chooses otherwise. Never silently switch to a paid provider.
7. Process long media as resumable chunks with overlap, bounded retries, persisted job progress, correct timestamp offsets, and safe transcript merging.
8. Provide editable transcript text and timestamped segments, speaker labels where the provider supports them, search/replace, undo/redo, autosave, and version restore.
9. Export TXT, SRT, VTT, JSON, CSV, PDF, and DOCX.
10. Provide a clear setup/run guide written for an Android + Termux user, with honest provider, format, duration, and Android background-process limitations.

## Non-goals

- No public deployment, Vercel, Supabase, Cloudflare Workers, D1, R2, or Queues.
- No multi-user registration, account system, remote sharing, or cloud media storage.
- No promise of unlimited free transcription: Groq limits, file restrictions, and API availability still apply.
- No claim that full-quality offline speech recognition is available on every Android phone.
- No YouTube audio downloading or bypassing platform restrictions.
- No always-on background guarantee from Android; jobs must be recoverable after the app/server is restarted.

## Approaches considered

1. **Termux local web app (chosen):** Keep React/Vite, add a local Python API using FastAPI, Python's built-in SQLite, local filesystem storage, and FFmpeg installed in Termux. This avoids native Node SQLite build problems on Android and keeps the familiar browser UI. Trade-off: Termux must remain installed and the local server must be started when needed.
2. Native Android APK: better system integration and microphone/file access, but requires a separate Android app project and more build/signing work. It would discard much of the existing React app.
3. Fully offline AI stack: avoids sending media to external providers, but large ASR models may exceed typical phone RAM/storage and can be too slow. Keep as a future optional experiment, not a launch requirement.

## Architecture

- **Frontend:** Existing React 19 + Vite + TypeScript; retain and adapt the dark glass UI. Add browser MediaRecorder capture and editor/export controls.
- **Local backend:** Python 3 + FastAPI, listening on `127.0.0.1` only by default. API routes handle projects, uploads, recordings, settings, provider calls, jobs, transcript edits, history, and exports.
- **Database:** Python standard-library `sqlite3`; schema/migrations stored in the repository. No native SQLite Node addon.
- **Media pipeline:** FFmpeg/ffprobe installed through Termux packages. Extract audio, convert to a provider-compatible format, split large audio into bounded chunks with overlap, persist chunk state, retry transient provider failures, and merge timestamp offsets deterministically.
- **AI providers:** Groq audio transcription; Gemini-compatible text analysis configured explicitly. Keys stored in a local ignored `.env` file and read only by the backend.
- **Persistence:** `data/transcript-studio.sqlite3`, `data/media/`, and `data/exports/` in the private Termux project directory. Add ignore rules and a backup/export path. The user can delete individual projects and all local data.
- **Jobs:** A database-backed local worker loop resumes queued/interrupted jobs at startup. One or a small configurable number of jobs run at once to protect phone resources.
- **YouTube:** Attempt only supported caption retrieval; do not fetch or download media streams. If there are no accessible captions, ask the user to upload a permitted media file instead.

## Main user experience

- Dashboard with recent projects, status, search, and a clear new-transcription area.
- Source choices: upload file, record microphone, or paste a YouTube URL for available captions.
- Per-job controls: language, model, timestamps, chunking behavior, and provider selection.
- Transcript workspace with editable text, segment timeline, timestamp seek when media is present, speaker labels, search/replace, undo/redo, save state, and version history.
- AI tools panel for cleanup, summary, translation, chapters, keywords, and content analysis. Each operation is explicit and preserves the original transcript.
- Export controls for all requested formats.
- Settings for provider keys/status, defaults, storage location/usage, retention, and data deletion.

## Security and privacy

- Bind to `127.0.0.1` by default; do not expose the API to the LAN without a deliberate future setting.
- No login is needed for a single-user local app, but validate request origins/hosts and use a per-run local CSRF token for write routes where practical.
- Validate file extensions, MIME types, size, and generated paths; never trust a user-supplied path.
- Keep API keys out of Vite/browser environment variables, logs, exports, and Git.
- Display a confirmation before sending audio to Groq or transcript text to Gemini.
- Delete media and derived data when a project is deleted; provide a separate all-data deletion action.

## Delivery stages

1. **Local foundation:** Remove Cloudflare Worker/runtime dependencies, add Termux start scripts, FastAPI API, SQLite schema, local project CRUD, health/config status, and update README. App runs locally without provider keys.
2. **Transcription pipeline:** Add uploads, FFmpeg probing/conversion, Groq transcription, chunked processing, retries/resume, and recording upload.
3. **Transcript workspace:** Add segment-aware editing, autosave, undo/redo, version history, search/replace, and timestamp seeking.
4. **AI and exports:** Add explicit Gemini text tools and all requested export formats.
5. **YouTube captions and hardening:** Add permitted caption-only URL flow, error handling, data deletion/backup, mobile QA, and complete documentation.

Each stage must be buildable and testable before the next is considered complete. Features that depend on external API keys must show a clear unconfigured state rather than fake success.

## Acceptance criteria

- Fresh Termux setup instructions install the required tools and start the app from a local URL.
- No Cloudflare, Supabase, Vercel, or external database service is needed to open the app and manage local projects.
- No API key is shipped to the browser or committed.
- Local project CRUD survives server restarts.
- File and microphone input produce a real transcription when Groq is configured; otherwise the UI explains what is missing.
- Long jobs persist chunk progress and resume without duplicating already completed chunks.
- Transcript edits and versions survive reloads.
- SRT/VTT timestamps remain valid after chunk merge and export.
- All export formats are generated from the saved transcript, not placeholder content.
- YouTube URL handling never downloads video/audio or bypasses platform restrictions.
- Automated tests cover API, job recovery, chunk timestamp merging, exports, and core editor state; build/typecheck pass.
