export type TranscriptSegment = { id: string; start: number; end: number; text: string; speaker?: string };
export type TranscriptionResult = { text: string; language?: string; duration?: number; segments: TranscriptSegment[]; provider: "groq"; model: string };
