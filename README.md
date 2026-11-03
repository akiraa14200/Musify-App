# Transcript Studio

A free-first AI audio/video transcription workspace built on Cloudflare Workers, D1, R2 and Queues. It does not use Vercel or Supabase.

## Current implementation

- Vite + React + TypeScript responsive dashboard.
- Email/password registration and login with server-side PBKDF2 password hashing and HttpOnly session cookies.
- D1-backed user, session, project, transcript, segment and job records.
- R2 media storage with user-scoped object keys.
- Queue-backed asynchronous Groq Whisper transcription.
- Whisper Large V3 and Large V3 Turbo selection.
- Automatic language detection and explicit language choices.
- Timestamped segment persistence when returned by the provider.
- Editable transcript with debounced autosave.
- Project list, status refresh, delete project/media, TXT and SRT exports.
- API ownership checks on every project operation.
- No mock transcript data or simulated completion.

## Important current limitations

- Direct upload is limited to 20 MB. The app does not yet implement browser-to-R2 multipart uploads or long-media chunking.
- The worker forwards one audio/video file to Groq. Provider size, format, duration, quota and timeout limits still apply.
- YouTube URL ingestion and caption retrieval are not enabled. No platform restrictions are bypassed.
- Speaker diarization, microphone recording, AI summary/translation/chapters/keywords, undo/redo, version history, PDF/DOCX/JSON/CSV/VTT exports and full subtitle editor are not implemented yet.
- Email accounts are not email-verified in this initial version. Use a unique password; add an email delivery provider before public launch.
- Queue processing and API calls require configured Cloudflare resources and secrets. The repository cannot prove production operation until deployed and tested with your credentials.

## Stack

- Frontend: Vite, React, TypeScript, Lucide.
- Hosting/API: Cloudflare Workers with static assets.
- Database: Cloudflare D1 (SQLite).
- Media storage: Cloudflare R2.
- Background processing: Cloudflare Queues.
- Transcription: Groq Whisper API.

## Cloudflare setup

Install Node.js 20+ and dependencies:

```bash
npm install
```

Authenticate Wrangler:

```bash
npx wrangler login
```

Create resources:

```bash
npx wrangler d1 create transcript-studio-db
npx wrangler r2 bucket create transcript-studio-media
npx wrangler queues create transcript-studio-jobs
```

Copy the D1 database ID printed by Wrangler into `wrangler.jsonc` in place of `REPLACE_WITH_D1_DATABASE_ID`.

Apply the database migration:

```bash
npx wrangler d1 migrations apply transcript-studio-db --remote
```

Set secrets (values are never stored in Git):

```bash
npx wrangler secret put GROQ_API_KEY
```

Optional Gemini key for future text tools:

```bash
npx wrangler secret put GEMINI_API_KEY
```

Run locally:

```bash
npm run dev
```

Build and deploy directly to Cloudflare Workers:

```bash
npm run deploy
```

This deploy path uses Wrangler and Cloudflare Workers, not Vercel. Cloudflare account usage and service quotas apply.

## Local API development

For local Worker testing, use `npx wrangler dev`. Create a local `.dev.vars` file (gitignored) containing:

```text
GROQ_API_KEY=your_key
```

Configure D1 and R2 local bindings through Wrangler. Never commit `.dev.vars` or API keys.

## Security notes

- Passwords are derived with PBKDF2-SHA-256 and per-user random salts.
- Session identifiers are random and stored as SHA-256 hashes in D1.
- Session cookies are HttpOnly, Secure and SameSite=Lax.
- Project queries always include the authenticated user ID.
- R2 media keys include the owning user ID and are only read/deleted from authenticated server routes or queue messages.
- Provider secrets are Worker secrets, not browser environment variables.
- Add email verification, CSRF hardening, rate limiting, account recovery and abuse controls before opening registration publicly.
