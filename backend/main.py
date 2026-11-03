from __future__ import annotations
import os, re, json, uuid, time, sqlite3, shutil, subprocess, threading
from pathlib import Path
from datetime import datetime, timezone
from typing import Optional
import httpx
from dotenv import load_dotenv
from fastapi import FastAPI, UploadFile, File, Form, HTTPException, BackgroundTasks
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import PlainTextResponse, Response
from pydantic import BaseModel

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"
MEDIA = DATA / "media"
EXPORTS = DATA / "exports"
for p in (DATA, MEDIA, EXPORTS): p.mkdir(parents=True, exist_ok=True)
load_dotenv(ROOT / ".env")
DB = DATA / "transcript-studio.sqlite3"
app = FastAPI(title="Transcript Studio Local API")
app.add_middleware(CORSMiddleware, allow_origins=["http://127.0.0.1:5173","http://localhost:5173"], allow_methods=["*"], allow_headers=["*"])

def connect():
    c=sqlite3.connect(DB, timeout=30); c.row_factory=sqlite3.Row; c.execute("PRAGMA foreign_keys=ON"); return c

def init_db():
    with connect() as c:
        c.executescript("""
        CREATE TABLE IF NOT EXISTS projects(id TEXT PRIMARY KEY,title TEXT NOT NULL,source_type TEXT NOT NULL,source_name TEXT,media_path TEXT,media_size INTEGER,language TEXT DEFAULT 'auto',model TEXT DEFAULT 'whisper-large-v3',status TEXT DEFAULT 'queued',progress INTEGER DEFAULT 0,current_step TEXT,error_message TEXT,created_at TEXT DEFAULT CURRENT_TIMESTAMP,updated_at TEXT DEFAULT CURRENT_TIMESTAMP);
        CREATE TABLE IF NOT EXISTS transcripts(id TEXT PRIMARY KEY,project_id TEXT UNIQUE REFERENCES projects(id) ON DELETE CASCADE,original_text TEXT DEFAULT '',edited_text TEXT DEFAULT '',language TEXT,word_count INTEGER DEFAULT 0,created_at TEXT DEFAULT CURRENT_TIMESTAMP,updated_at TEXT DEFAULT CURRENT_TIMESTAMP);
        CREATE TABLE IF NOT EXISTS segments(id TEXT PRIMARY KEY,transcript_id TEXT REFERENCES transcripts(id) ON DELETE CASCADE,start_time REAL,end_time REAL,text TEXT,speaker TEXT,sequence_number INTEGER);
        CREATE TABLE IF NOT EXISTS versions(id TEXT PRIMARY KEY,transcript_id TEXT REFERENCES transcripts(id) ON DELETE CASCADE,text TEXT,created_at TEXT DEFAULT CURRENT_TIMESTAMP);
        CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY,project_id TEXT REFERENCES projects(id) ON DELETE CASCADE,status TEXT,progress INTEGER DEFAULT 0,step TEXT,error TEXT,created_at TEXT DEFAULT CURRENT_TIMESTAMP);
        """)
init_db()

def rowdict(row): return dict(row) if row else None
def get_project(pid):
    with connect() as c: return rowdict(c.execute("SELECT * FROM projects WHERE id=?",(pid,)).fetchone())
def update_job(pid,status,progress,step,error=None):
    with connect() as c:
        c.execute("UPDATE projects SET status=?,progress=?,current_step=?,error_message=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",(status,progress,step,error,pid))
        c.execute("UPDATE jobs SET status=?,progress=?,step=?,error=? WHERE project_id=?",(status,progress,step,error,pid))

def groq_transcribe(pid):
    p=get_project(pid)
    if not p or p["status"]=="completed": return
    key=os.getenv("GROQ_API_KEY","").strip()
    if not key:
        update_job(pid,"failed",0,"Groq API key required","Add GROQ_API_KEY to the local .env file, then retry this project.")
        return
    media=Path(p["media_path"])
    if not media.exists():
        update_job(pid,"failed",0,"Media missing","The local media file could not be found."); return
    chunks=[]
    try:
        update_job(pid,"processing",3,"Checking media")
        ffmpeg=shutil.which("ffmpeg")
        source=media
        if ffmpeg:
            probe=subprocess.run(["ffprobe","-v","error","-show_entries","format=duration","-of","default=noprint_wrappers=1:nokey=1",str(media)],capture_output=True,text=True,timeout=30)
            duration=float(probe.stdout.strip() or 0)
            # Chunk at 9 minutes to stay comfortably below common provider limits.
            if duration>540:
                outdir=MEDIA/pid/"chunks"; outdir.mkdir(parents=True,exist_ok=True)
                subprocess.run(["ffmpeg","-y","-i",str(media),"-vn","-ac","1","-ar","16000","-f","segment","-segment_time","540","-reset_timestamps","1",str(outdir/"part_%04d.mp3")],capture_output=True,check=True,timeout=max(120,int(duration*2)))
                chunks=sorted(outdir.glob("part_*.mp3"))
            else: chunks=[media]
        else: chunks=[media]
        if not chunks: chunks=[media]
        all_text=[]; all_segments=[]; offset=0.0
        for i,chunk in enumerate(chunks):
            update_job(pid,"processing",min(10+int(i/len(chunks)*80),90),f"Transcribing part {i+1} of {len(chunks)}")
            with chunk.open("rb") as f:
                files={"file":(chunk.name,f,"audio/mpeg" if chunk.suffix==".mp3" else "application/octet-stream")}
                data={"model":p["model"],"response_format":"verbose_json"}
                if p["language"]!="auto": data["language"]=p["language"]
                r=httpx.post("https://api.groq.com/openai/v1/audio/transcriptions",headers={"Authorization":f"Bearer {key}"},data=data,files=files,timeout=300)
            if r.status_code==429: raise RuntimeError("Groq rate limit or free quota reached. Try again later.")
            if r.status_code>=400: raise RuntimeError(f"Groq returned HTTP {r.status_code}: {r.text[:350]}")
            result=r.json(); all_text.append(result.get("text",""))
            for seg in result.get("segments",[]) or []:
                all_segments.append((offset+float(seg.get("start",0)),offset+float(seg.get("end",0)),seg.get("text","").strip()))
            if len(chunks)>1:
                pr=subprocess.run(["ffprobe","-v","error","-show_entries","format=duration","-of","default=noprint_wrappers=1:nokey=1",str(chunk)],capture_output=True,text=True)
                offset+=float(pr.stdout.strip() or 540)
        full=" ".join(x.strip() for x in all_text if x.strip()).strip()
        tid=str(uuid.uuid4())
        with connect() as c:
            c.execute("INSERT OR REPLACE INTO transcripts(id,project_id,original_text,edited_text,language,word_count) VALUES(?,?,?,?,?,?)",(tid,pid,full,full,p["language"],len(full.split())))
            c.execute("DELETE FROM segments WHERE transcript_id=?",(tid,))
            c.executemany("INSERT INTO segments(id,transcript_id,start_time,end_time,text,sequence_number) VALUES(?,?,?,?,?,?)",[(str(uuid.uuid4()),tid,a,b,t,i) for i,(a,b,t) in enumerate(all_segments)])
        update_job(pid,"completed",100,"Transcript ready")
    except Exception as e:
        update_job(pid,"failed",0,"Transcription failed",str(e)[:600])

@app.get("/api/health")
def health():
    return {"ok":True,"mode":"local","transcriptionProvider":"Groq configured" if os.getenv("GROQ_API_KEY") else "Groq key not set","textProvider":"Gemini configured" if os.getenv("GEMINI_API_KEY") else "Gemini key not set","ffmpeg":bool(shutil.which("ffmpeg")),"storage":str(DATA)}
@app.get("/api/projects")
def projects():
    with connect() as c: rows=c.execute("SELECT * FROM projects ORDER BY updated_at DESC").fetchall()
    return {"projects":[dict(x) for x in rows]}
@app.post("/api/projects")
async def create_project(background:BackgroundTasks,file:UploadFile=File(...),title:str=Form(""),language:str=Form("auto"),model:str=Form("whisper-large-v3"),timestamps:str=Form("true")):
    name=Path(file.filename or "upload.bin").name
    if not re.search(r"\.(mp3|wav|m4a|flac|ogg|mp4|webm|mov|mkv|aac)$",name,re.I): raise HTTPException(415,"Unsupported media format.")
    pid=str(uuid.uuid4()); folder=MEDIA/pid; folder.mkdir(parents=True,exist_ok=True); dest=folder/name
    size=0
    with dest.open("wb") as out:
        while block:=await file.read(1024*1024):
            size+=len(block)
            if size>2*1024*1024*1024: dest.unlink(missing_ok=True); raise HTTPException(413,"File exceeds the 2 GB local safety limit.")
            out.write(block)
    model=model if model in ("whisper-large-v3","whisper-large-v3-turbo") else "whisper-large-v3"
    with connect() as c:
        c.execute("INSERT INTO projects(id,title,source_type,source_name,media_path,media_size,language,model,status,progress,current_step) VALUES(?,?,?,?,?,?,?,?,?,?,?)",(pid,title.strip() or Path(name).stem,"upload",name,str(dest),size,language,model,"queued",0,"Waiting for worker"))
        c.execute("INSERT INTO jobs(id,project_id,status,step) VALUES(?,?,?,?)",(str(uuid.uuid4()),pid,"queued","Waiting for worker"))
    background.add_task(groq_transcribe,pid)
    return {"project":get_project(pid)}
@app.get("/api/projects/{pid}")
def project_detail(pid:str):
    p=get_project(pid)
    if not p: raise HTTPException(404,"Project not found")
    with connect() as c:
        tr=c.execute("SELECT * FROM transcripts WHERE project_id=?",(pid,)).fetchone()
        seg=c.execute("SELECT * FROM segments WHERE transcript_id=? ORDER BY sequence_number",(tr["id"],)).fetchall() if tr else []
        versions=c.execute("SELECT id,created_at FROM versions WHERE transcript_id=? ORDER BY created_at DESC LIMIT 30",(tr["id"],)).fetchall() if tr else []
    return {"project":p,"transcript":rowdict(tr),"segments":[dict(x) for x in seg],"versions":[dict(x) for x in versions],"job":{"status":p["status"],"progress":p["progress"],"current_step":p["current_step"],"error_message":p["error_message"]}}
class Edit(BaseModel):
    editedText:Optional[str]=None
    title:Optional[str]=None
@app.patch("/api/projects/{pid}")
def edit_project(pid:str,body:Edit):
    p=get_project(pid)
    if not p: raise HTTPException(404,"Project not found")
    with connect() as c:
        if body.title is not None: c.execute("UPDATE projects SET title=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",(body.title[:160],pid))
        if body.editedText is not None:
            tr=c.execute("SELECT * FROM transcripts WHERE project_id=?",(pid,)).fetchone()
            if not tr: raise HTTPException(409,"Transcript is not ready yet")
            if tr["edited_text"]!=body.editedText:
                c.execute("INSERT INTO versions(id,transcript_id,text) VALUES(?,?,?)",(str(uuid.uuid4()),tr["id"],tr["edited_text"]))
                c.execute("UPDATE transcripts SET edited_text=?,word_count=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",(body.editedText,tr["id"] and len(body.editedText.split()),tr["id"]))
    return {"ok":True}
@app.post("/api/projects/{pid}/retry")
def retry(pid:str,background:BackgroundTasks):
    p=get_project(pid)
    if not p: raise HTTPException(404,"Project not found")
    update_job(pid,"queued",0,"Retry queued"); background.add_task(groq_transcribe,pid); return {"ok":True}
@app.post("/api/projects/{pid}/restore/{version_id}")
def restore(pid:str,version_id:str):
    with connect() as c:
        tr=c.execute("SELECT id FROM transcripts WHERE project_id=?",(pid,)).fetchone()
        v=c.execute("SELECT text FROM versions WHERE id=? AND transcript_id=?",(version_id,tr["id"] if tr else "")).fetchone()
        if not v: raise HTTPException(404,"Version not found")
        c.execute("INSERT INTO versions(id,transcript_id,text) VALUES(?,?,?)",(str(uuid.uuid4()),tr["id"],c.execute("SELECT edited_text FROM transcripts WHERE id=?",(tr["id"],)).fetchone()[0]))
        c.execute("UPDATE transcripts SET edited_text=?,word_count=? WHERE id=?",(v["text"],len(v["text"].split()),tr["id"]))
    return {"ok":True}
@app.delete("/api/projects/{pid}")
def delete_project(pid:str):
    p=get_project(pid)
    if not p: raise HTTPException(404,"Project not found")
    with connect() as c: c.execute("DELETE FROM projects WHERE id=?",(pid,))
    shutil.rmtree(MEDIA/pid,ignore_errors=True); return {"ok":True}
@app.get("/api/projects/{pid}/export/{fmt}")
def export_project(pid:str,fmt:str):
    p=get_project(pid)
    if not p: raise HTTPException(404,"Project not found")
    with connect() as c:
        tr=c.execute("SELECT * FROM transcripts WHERE project_id=?",(pid,)).fetchone()
        seg=c.execute("SELECT * FROM segments WHERE transcript_id=? ORDER BY sequence_number",(tr["id"],)).fetchall() if tr else []
    if not tr: raise HTTPException(409,"Transcript not ready")
    text=tr["edited_text"]; lines=[]
    def stamp(sec,comma=False):
        h=int(sec//3600); m=int(sec%3600//60); s=int(sec%60); ms=int((sec-int(sec))*1000)
        return f"{h:02}:{m:02}:{s:02}{',' if comma else '.'}{ms:03}"
    if fmt=="txt": return PlainTextResponse(text,headers={"Content-Disposition":f'attachment; filename="{p["title"]}.txt"'})
    if fmt in ("srt","vtt"):
        for i,s in enumerate(seg,1):
            start,end=stamp(s["start_time"],fmt=="srt"),stamp(s["end_time"],fmt=="srt")
            lines.append((f"{i}\n" if fmt=="srt" else "")+f"{start} --> {end}\n{s['text']}\n")
        payload=("WEBVTT\n\n" if fmt=="vtt" else "")+"\n".join(lines)
        return PlainTextResponse(payload,media_type="text/vtt" if fmt=="vtt" else "application/x-subrip")
    if fmt=="json": return Response(json.dumps({"project":p["title"],"transcript":text,"segments":[dict(s) for s in seg]},ensure_ascii=False,indent=2),media_type="application/json")
    if fmt=="csv":
        import csv,io
        b=io.StringIO(); w=csv.writer(b); w.writerow(["start","end","text"]); [w.writerow([s["start_time"],s["end_time"],s["text"]]) for s in seg]
        return Response(b.getvalue(),media_type="text/csv")
    raise HTTPException(400,"Supported exports: txt, srt, vtt, json, csv. PDF/DOCX export is not enabled in this Android build yet.")
@app.post("/api/youtube")
def youtube_captions(body:dict):
    url=str(body.get("url","")); m=re.search(r"(?:v=|youtu\.be/|shorts/)([\w-]{11})",url)
    if not m: raise HTTPException(400,"Enter a valid YouTube video URL.")
    try:
        from youtube_transcript_api import YouTubeTranscriptApi
        data=YouTubeTranscriptApi().fetch(m.group(1))
        text=" ".join(x.text for x in data)
        return {"text":text,"language":getattr(data,"language_code","unknown"),"source":"public captions"}
    except Exception as e: raise HTTPException(422,"Accessible captions were not available for this video. No video/audio was downloaded. "+str(e)[:200])
@app.post("/api/ai/{action}")
def ai_text(action:str,body:dict):
    key=os.getenv("GEMINI_API_KEY","").strip()
    if not key: raise HTTPException(503,"Gemini is not configured. Add GEMINI_API_KEY to .env.")
    allowed={"clean":"Improve punctuation and readability without changing meaning.","summary":"Create a concise structured summary.","translate":"Translate faithfully into the requested target language.","chapters":"Create timestamp-aware chapter headings.","keywords":"Extract useful keywords and named topics.","analyze":"Analyze the content, themes, claims, and key takeaways."}
    if action not in allowed: raise HTTPException(404,"Unknown AI action")
    prompt=allowed[action]+"\nReturn only the requested result.\n\n"+str(body.get("text",""))[:100000]
    model=os.getenv("GEMINI_MODEL","gemini-2.0-flash")
    r=httpx.post(f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={key}",json={"contents":[{"parts":[{"text":prompt}]}]},timeout=120)
    if r.status_code>=400: raise HTTPException(r.status_code,"Gemini request failed: "+r.text[:300])
    data=r.json(); return {"text":data["candidates"][0]["content"]["parts"][0]["text"]}
@app.get("/api/settings")
def settings(): return {"groqConfigured":bool(os.getenv("GROQ_API_KEY")),"geminiConfigured":bool(os.getenv("GEMINI_API_KEY")),"dataDirectory":str(DATA),"databaseBytes":DB.stat().st_size if DB.exists() else 0,"mediaBytes":sum(x.stat().st_size for x in MEDIA.rglob("*") if x.is_file()),"ffmpeg":bool(shutil.which("ffmpeg"))}
@app.delete("/api/data")
def clear_data():
    with connect() as c: c.execute("DELETE FROM projects")
    shutil.rmtree(MEDIA,ignore_errors=True); MEDIA.mkdir(exist_ok=True)
    return {"ok":True,"message":"All projects and media deleted."}
