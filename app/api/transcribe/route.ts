import { NextRequest, NextResponse } from "next/server";
import { transcribeWithGroq } from "@/lib/providers/groq";

export const runtime = "nodejs";
export const maxDuration = 120;
const MAX_FILE_BYTES = 24 * 1024 * 1024;
const ALLOWED = /\.(mp3|wav|m4a|flac|ogg|mp4|mov|webm|mkv)$/i;

export async function POST(request: NextRequest) {
  try {
    const form = await request.formData();
    const file = form.get("file");
    if (!(file instanceof File)) return NextResponse.json({ error: "Choose an audio or video file first." }, { status: 400 });
    if (!file.size) return NextResponse.json({ error: "The selected file is empty." }, { status: 400 });
    if (file.size > MAX_FILE_BYTES) return NextResponse.json({ error: "This first version accepts files up to 24 MB. Long-file chunking is not enabled yet." }, { status: 413 });
    if (!ALLOWED.test(file.name)) return NextResponse.json({ error: "Unsupported file type." }, { status: 415 });
    const result = await transcribeWithGroq(file, {
      language: String(form.get("language") || "auto"),
      model: String(form.get("model") || "whisper-large-v3"),
      timestamps: String(form.get("timestamps") || "true") === "true"
    });
    return NextResponse.json(result);
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unexpected transcription error.";
    return NextResponse.json({ error: message }, { status: 500 });
  }
}
