import type { TranscriptSegment, TranscriptionResult } from "@/types/transcript";

type GroqResponse = {
  text?: string;
  language?: string;
  duration?: number;
  segments?: Array<{ start?: number; end?: number; text?: string }>;
};

export async function transcribeWithGroq(file: File, options: { language?: string; model?: string; timestamps?: boolean }): Promise<TranscriptionResult> {
  const apiKey = process.env.GROQ_API_KEY;
  if (!apiKey) throw new Error("Groq is not configured. Add GROQ_API_KEY to the server environment.");
  const model = options.model || process.env.GROQ_TRANSCRIPTION_MODEL || "whisper-large-v3";
  const form = new FormData();
  form.append("file", file, file.name);
  form.append("model", model);
  form.append("response_format", options.timestamps ? "verbose_json" : "json");
  if (options.language && options.language !== "auto") form.append("language", options.language);

  const response = await fetch("https://api.groq.com/openai/v1/audio/transcriptions", {
    method: "POST",
    headers: { Authorization: "Bearer " + apiKey },
    body: form,
    signal: AbortSignal.timeout(120000),
    cache: "no-store"
  });
  if (!response.ok) {
    const body = await response.text().catch(() => "");
    if (response.status === 429) throw new Error("Groq rate limit or quota reached. Wait and retry.");
    if (response.status === 401) throw new Error("Groq authentication failed. Check the server-side API key.");
    throw new Error("Groq transcription failed (" + response.status + "): " + body.slice(0, 240));
  }
  const data = await response.json() as GroqResponse;
  const segments: TranscriptSegment[] = (data.segments || []).map((s, i) => ({
    id: "segment-" + (i + 1), start: Number(s.start || 0), end: Number(s.end || 0), text: String(s.text || "").trim()
  }));
  return { text: String(data.text || ""), language: data.language, duration: data.duration, segments, provider: "groq", model };
}
