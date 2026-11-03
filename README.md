# Transcript Studio

Free-first AI audio/video transcription workspace using Next.js, TypeScript, Groq Whisper and a Supabase schema foundation.

## Implemented in this first replacement commit

- Next.js App Router and TypeScript foundation.
- Responsive dark workspace interface.
- Real server-side Groq Whisper transcription endpoint.
- Large V3 and Large V3 Turbo model selection.
- Language selection and verbose timestamp response.
- Editable transcript text area and TXT export.
- Supabase schema draft with owner-scoped RLS policies.
- Environment variable template and secret-safe gitignore.

## Not yet implemented

Supabase Auth and persistence, media storage, background worker, resumable chunked uploads, chunk overlap reconciliation, YouTube authorized captions, microphone recording, diarization, editor undo/redo and autosave, Gemini text tools, subtitle formats, PDF/DOCX/JSON/CSV exports, automated integration tests.

The UI labels unfinished features and does not show fake project history or simulated AI output.

## Local setup

1. Use Node.js 20.9 or newer.
2. Copy .env.example to .env.local.
3. Set GROQ_API_KEY server-side.
4. Run npm install, then npm run dev.
5. Open http://localhost:3000.

The direct API route is intentionally limited to 4 MB to fit typical Vercel serverless request limits. Larger media requires direct-to-storage uploads and a background worker, which are not implemented yet. Provider quotas and model availability can change.

## Environment variables

- GROQ_API_KEY: server-side Groq key.
- GROQ_TRANSCRIPTION_MODEL: default Whisper model.
- GEMINI_API_KEY and GEMINI_MODEL: reserved for upcoming text tools.
- NEXT_PUBLIC_SUPABASE_URL and NEXT_PUBLIC_SUPABASE_ANON_KEY: Supabase client configuration.
- SUPABASE_SERVICE_ROLE_KEY: server-only elevated key; never expose it in browser code.
- TRANSCRIPTION_WORKER_URL and TRANSCRIPTION_WORKER_SECRET: optional future worker integration.

## Supabase

Run supabase/schema.sql in the Supabase SQL editor. Authentication and persistence code must be completed before relying on user project storage. Review RLS policies before production use.

## Vercel deployment

Import this repository into Vercel with the Next.js preset. Configure GROQ_API_KEY in project environment variables before testing transcription. No secrets belong in source control.

## YouTube and privacy

Direct YouTube audio extraction is not enabled. An authorized captions integration is still required. Do not bypass DRM or platform restrictions. Files sent through the transcription route are forwarded to Groq and are not persisted by this starter.
