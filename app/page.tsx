"use client";

import { useRef, useState } from "react";
import { AudioLines, Check, FileAudio, Film, Gauge, Languages, Mic, Settings2, Sparkles, UploadCloud, WandSparkles, X } from "lucide-react";
import type { TranscriptionResult } from "@/types/transcript";

export default function Home() {
  const inputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [language, setLanguage] = useState("auto");
  const [model, setModel] = useState("whisper-large-v3");
  const [timestamps, setTimestamps] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState<TranscriptionResult | null>(null);
  const [transcript, setTranscript] = useState("");

  async function start() {
    if (!file || busy) return;
    setBusy(true); setError(""); setResult(null);
    const form = new FormData();
    form.append("file", file); form.append("language", language);
    form.append("model", model); form.append("timestamps", String(timestamps));
    try {
      const response = await fetch("/api/transcribe", { method: "POST", body: form });
      const data = await response.json();
      if (!response.ok) throw new Error(data.error || "Transcription failed.");
      setResult(data as TranscriptionResult); setTranscript(String(data.text || ""));
    } catch (e) { setError(e instanceof Error ? e.message : "Unexpected error."); }
    finally { setBusy(false); }
  }

  function exportTxt() {
    const blob = new Blob([transcript], { type: "text/plain;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a"); a.href = url; a.download = "transcript.txt"; a.click();
    URL.revokeObjectURL(url);
  }

  return <main className="mx-auto min-h-screen max-w-[1440px] px-4 pb-12 pt-6 sm:px-8">
    <header className="mb-10 flex items-center justify-between">
      <div className="flex items-center gap-3"><div className="grid h-11 w-11 place-items-center rounded-2xl border border-white/10 bg-white/[.07] text-indigo-200"><AudioLines size={22}/></div><div><div className="font-semibold">Transcript Studio</div><div className="text-xs muted">AI-powered workspace</div></div></div>
      <button className="ghost flex items-center gap-2 text-sm"><Settings2 size={15}/> Settings</button>
    </header>
    <div className="mb-8"><p className="mb-2 text-xs font-semibold uppercase tracking-[.2em] text-indigo-200">Your workspace</p><h1 className="text-3xl font-semibold tracking-tight sm:text-4xl">Turn media into <span className="text-indigo-200">meaning.</span></h1><p className="mt-2 max-w-xl text-sm leading-6 muted">Transcribe audio and video, refine every word, and shape the result into something useful.</p></div>
    <div className="grid gap-5 lg:grid-cols-[minmax(0,1.5fr)_minmax(270px,.8fr)]">
      <section className="glass rounded-3xl p-5 sm:p-7">
        <div className="mb-6"><h2 className="text-lg font-semibold">New transcription</h2><p className="mt-1 text-xs muted">Upload media you own or are authorized to process.</p></div>
        <button onClick={() => inputRef.current?.click()} className="mb-4 w-full rounded-2xl border border-dashed border-indigo-200/30 bg-indigo-200/[.05] p-6 text-left hover:bg-indigo-200/[.09]"><div className="mb-3 grid h-10 w-10 place-items-center rounded-xl bg-indigo-200/10 text-indigo-200"><UploadCloud size={20}/></div><div className="text-sm font-semibold">Upload audio or video</div><div className="mt-1 text-xs muted">MP3, WAV, M4A, FLAC, OGG, MP4, MOV, WEBM, MKV</div></button>
        <input ref={inputRef} type="file" accept="audio/*,video/*,.mkv,.flac" className="hidden" onChange={e => { const f=e.target.files?.[0]; if(f){setFile(f);setResult(null);setTranscript("");setError("");} }}/>
        {file && <div className="mb-4 flex items-center gap-3 rounded-xl border border-indigo-200/20 bg-indigo-200/[.05] p-3"><FileAudio size={18}/><div className="min-w-0 flex-1"><div className="truncate text-sm">{file.name}</div><div className="text-xs muted">{(file.size/1048576).toFixed(2)} MB</div></div><button className="ghost !p-2" onClick={()=>setFile(null)}><X size={14}/></button></div>}
        <div className="mb-4 grid gap-3 sm:grid-cols-2"><label className="text-xs text-slate-300">Language<select className="field mt-2" value={language} onChange={e=>setLanguage(e.target.value)}><option value="auto">Auto detect</option><option value="en">English</option><option value="hi">Hindi</option><option value="ja">Japanese</option><option value="ko">Korean</option><option value="zh">Chinese</option><option value="es">Spanish</option><option value="fr">French</option></select></label><label className="text-xs text-slate-300">Model<select className="field mt-2" value={model} onChange={e=>setModel(e.target.value)}><option value="whisper-large-v3">Whisper Large V3 · Accuracy</option><option value="whisper-large-v3-turbo">Whisper Large V3 Turbo · Speed</option></select></label></div>
        <label className="flex items-center gap-3 text-sm text-slate-300"><input type="checkbox" checked={timestamps} onChange={e=>setTimestamps(e.target.checked)} className="accent-indigo-200"/> Include timestamps</label>
        <button onClick={start} disabled={!file || busy} className="primary mt-6 flex w-full items-center justify-center gap-2"><WandSparkles size={17}/>{busy?"Transcribing…":"Generate transcript"}</button>
        {busy && <p className="mt-3 text-xs text-indigo-200">Waiting for the selected Groq model. No simulated progress is shown.</p>}
        {error && <p className="mt-4 rounded-xl border border-rose-300/20 bg-rose-300/[.06] p-3 text-sm text-rose-200">{error}</p>}
        {result && <p className="mt-4 flex items-center gap-2 text-xs text-emerald-200"><Check size={14}/> Received from {result.provider} · {result.model}</p>}
      </section>
      <aside className="space-y-5">
        <section className="glass rounded-3xl p-5"><div className="mb-4 flex items-center gap-2"><Gauge size={17} className="text-indigo-200"/><h3 className="text-sm font-semibold">Engine status</h3></div><div className="space-y-3 text-xs"><div className="flex justify-between"><span className="muted">Provider</span><span>Groq Whisper</span></div><div className="flex justify-between"><span className="muted">Model</span><span>{model}</span></div><div className="flex justify-between"><span className="muted">API key</span><span className="text-amber-200">Configure server env</span></div><div className="flex justify-between"><span className="muted">Background worker</span><span className="muted">Not configured</span></div></div></section>
        <section className="glass rounded-3xl p-5"><div className="mb-3 flex items-center gap-2"><Sparkles size={17} className="text-indigo-200"/><h3 className="text-sm font-semibold">AI toolkit</h3></div><div className="space-y-3 text-xs"><div className="flex items-center gap-2"><Languages size={15}/> Translation <span className="ml-auto muted">Planned</span></div><div className="flex items-center gap-2"><Film size={15}/> Smart chapters <span className="ml-auto muted">Planned</span></div><div className="flex items-center gap-2"><Mic size={15}/> Recording <span className="ml-auto muted">Planned</span></div></div></section>
        <section className="rounded-2xl border border-white/[.07] p-5"><p className="text-xs leading-5 muted">Direct uploads are currently limited to 4 MB to fit typical serverless request limits. Larger media needs the planned direct-to-storage and background-worker pipeline. YouTube captions and Supabase persistence are not enabled yet. Provider quotas may change.</p></section>
      </aside>
    </div>
    {result && <section className="glass mt-5 rounded-3xl p-5 sm:p-7"><div className="mb-4 flex flex-wrap items-center justify-between gap-3"><div><h2 className="font-semibold">Transcript editor</h2><p className="mt-1 text-xs muted">{transcript.trim()?transcript.trim().split(/\s+/).length:0} words · {result.segments.length} timestamped segments</p></div><button onClick={exportTxt} className="ghost text-xs">Export TXT</button></div><textarea value={transcript} onChange={e=>setTranscript(e.target.value)} className="field min-h-64 resize-y text-sm leading-7"/>{result.segments.length>0 && <div className="mt-4 max-h-56 space-y-2 overflow-y-auto border-t border-white/10 pt-4">{result.segments.map(s=><div key={s.id} className="flex gap-3 text-xs"><span className="font-mono text-indigo-200">{Math.floor(s.start/60).toString().padStart(2,"0")}:{Math.floor(s.start%60).toString().padStart(2,"0")}</span><span className="text-slate-300">{s.text}</span></div>)}</div>}</section>}
    <footer className="mt-10 border-t border-white/[.07] pt-5 text-xs muted">Transcript Studio · Free-first infrastructure · External provider processing is disclosed</footer>
  </main>;
}
