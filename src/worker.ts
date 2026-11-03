/// <reference types="@cloudflare/workers-types" />

interface Env {
  DB: D1Database;
  MEDIA: R2Bucket;
  TRANSCRIPT_QUEUE: Queue<TranscriptJob>;
  ASSETS: Fetcher;
  GROQ_API_KEY: string;
  GEMINI_API_KEY?: string;
}
interface TranscriptJob {
  projectId: string;
  userId: string;
  mediaKey: string;
  fileName: string;
  model: string;
  language: string;
  timestamps: boolean;
}
type User = { id: string; email: string; display_name: string | null };
type Project = {
  id: string; title: string; source_type: string; source_name: string | null;
  media_size: number | null; language: string; selected_model: string; status: string;
  error_message: string | null; created_at: string; updated_at: string;
};
const MAX_UPLOAD = 20 * 1024 * 1024;
const COOKIE = "ts_session";
const allowedExt = /\.(mp3|wav|m4a|flac|ogg|mp4|webm)$/i;

function json(data: unknown, status = 200, headers: HeadersInit = {}) {
  return new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...headers } });
}
function cookie(token: string, maxAge: number) {
  return `${COOKIE}=${token}; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=${maxAge}`;
}
function randomToken() {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return Array.from(bytes, b => b.toString(16).padStart(2, "0")).join("");
}
async function digest(value: string) {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(bytes), b => b.toString(16).padStart(2, "0")).join("");
}
async function passwordHash(password: string, salt: string) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", salt: new TextEncoder().encode(salt), iterations: 210000, hash: "SHA-256" }, key, 256);
  return Array.from(new Uint8Array(bits), b => b.toString(16).padStart(2, "0")).join("");
}
function getCookie(request: Request, name: string) {
  const value = request.headers.get("Cookie") || "";
  return value.split(";").map(v => v.trim()).find(v => v.startsWith(name + "="))?.slice(name.length + 1);
}
async function currentUser(request: Request, env: Env): Promise<User | null> {
  const token = getCookie(request, COOKIE);
  if (!token) return null;
  const tokenHash = await digest(token);
  return await env.DB.prepare(`SELECT u.id, u.email, u.display_name FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.id=? AND s.expires_at > datetime('now')`).bind(tokenHash).first<User>();
}
async function createSession(userId: string, env: Env) {
  const token = randomToken();
  await env.DB.prepare("INSERT INTO sessions (id,user_id,expires_at) VALUES (?,?,datetime('now','+30 days'))").bind(await digest(token), userId).run();
  return token;
}
async function requireUser(request: Request, env: Env) {
  const user = await currentUser(request, env);
  if (!user) throw new Error("AUTH_REQUIRED");
  return user;
}
function cleanTitle(value: string) { return value.trim().slice(0, 140) || "Untitled transcription"; }

async function handleApi(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const path = url.pathname;
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: { "access-control-allow-methods": "GET,POST,PATCH,DELETE,OPTIONS", "access-control-allow-headers": "content-type" } });
  if (path === "/api/health" && request.method === "GET") {
    return json({ ok: true, transcriptionProvider: env.GROQ_API_KEY ? "Groq configured" : "Groq key missing", textProvider: env.GEMINI_API_KEY ? "Gemini configured" : "Gemini not configured", storage: "Cloudflare R2", database: "Cloudflare D1", queue: "Cloudflare Queues" });
  }
  if (path === "/api/auth/register" && request.method === "POST") {
    const body = await request.json() as { email?: string; password?: string; displayName?: string };
    const email = String(body.email || "").trim().toLowerCase();
    const password = String(body.password || "");
    if (!/^\S+@\S+\.\S+$/.test(email) || password.length < 10) return json({ error: "Enter a valid email and a password of at least 10 characters." }, 400);
    const salt = randomToken().slice(0, 32);
    const id = crypto.randomUUID();
    try {
      await env.DB.prepare("INSERT INTO users (id,email,password_hash,password_salt,display_name) VALUES (?,?,?,?,?)").bind(id, email, await passwordHash(password, salt), salt, String(body.displayName || "").trim().slice(0, 80) || null).run();
    } catch { return json({ error: "An account with this email may already exist." }, 409); }
    const token = await createSession(id, env);
    return json({ user: { id, email, display_name: body.displayName || null } }, 201, { "set-cookie": cookie(token, 60 * 60 * 24 * 30) });
  }
  if (path === "/api/auth/login" && request.method === "POST") {
    const body = await request.json() as { email?: string; password?: string };
    const email = String(body.email || "").trim().toLowerCase();
    const row = await env.DB.prepare("SELECT id,email,password_hash,password_salt,display_name FROM users WHERE email=?").bind(email).first<{id:string;email:string;password_hash:string;password_salt:string;display_name:string|null}>();
    if (!row || await passwordHash(String(body.password || ""), row.password_salt) !== row.password_hash) return json({ error: "Email or password is incorrect." }, 401);
    const token = await createSession(row.id, env);
    return json({ user: { id: row.id, email: row.email, display_name: row.display_name } }, 200, { "set-cookie": cookie(token, 60 * 60 * 24 * 30) });
  }
  if (path === "/api/auth/logout" && request.method === "POST") {
    const token = getCookie(request, COOKIE);
    if (token) await env.DB.prepare("DELETE FROM sessions WHERE id=?").bind(await digest(token)).run();
    return json({ ok: true }, 200, { "set-cookie": cookie("", 0) });
  }
  if (path === "/api/auth/me" && request.method === "GET") {
    const user = await currentUser(request, env);
    return json({ user });
  }

  let user: User;
  try { user = await requireUser(request, env); }
  catch { return json({ error: "Please sign in to continue." }, 401); }

  if (path === "/api/projects" && request.method === "GET") {
    const { results } = await env.DB.prepare("SELECT id,title,source_type,source_name,media_size,language,selected_model,status,error_message,created_at,updated_at FROM projects WHERE user_id=? ORDER BY updated_at DESC LIMIT 50").bind(user.id).all<Project>();
    return json({ projects: results });
  }
  if (path === "/api/projects" && request.method === "POST") {
    const form = await request.formData();
    const file = form.get("file");
    if (!(file instanceof File) || !file.size) return json({ error: "Choose an audio or video file." }, 400);
    if (file.size > MAX_UPLOAD) return json({ error: "This upload route currently accepts files up to 20 MB. Larger files need multipart R2 uploads and chunked worker processing." }, 413);
    if (!allowedExt.test(file.name)) return json({ error: "Unsupported file type. Use MP3, WAV, M4A, FLAC, OGG, MP4 or WEBM. MOV and MKV need conversion first." }, 415);
    if (!env.GROQ_API_KEY) return json({ error: "Groq is not configured. Add the GROQ_API_KEY Worker secret." }, 503);
    const id = crypto.randomUUID();
    const jobId = crypto.randomUUID();
    const mediaKey = `users/${user.id}/${id}/${crypto.randomUUID()}-${file.name.replace(/[^a-zA-Z0-9._-]/g, "_")}`;
    const title = cleanTitle(String(form.get("title") || file.name.replace(/\.[^.]+$/, "")));
    const language = String(form.get("language") || "auto").slice(0, 12);
    const model = ["whisper-large-v3", "whisper-large-v3-turbo"].includes(String(form.get("model"))) ? String(form.get("model")) : "whisper-large-v3";
    const timestamps = String(form.get("timestamps") || "true") === "true";
    try {
      await env.MEDIA.put(mediaKey, file.stream(), { httpMetadata: { contentType: file.type || "application/octet-stream" }, customMetadata: { owner: user.id, projectId: id } });
      await env.DB.batch([
        env.DB.prepare("INSERT INTO projects (id,user_id,title,source_type,source_name,media_key,media_size,language,selected_model,status) VALUES (?,?,?,?,?,?,?,?,?,'queued')").bind(id,user.id,title,"upload",file.name,mediaKey,file.size,language,model),
        env.DB.prepare("INSERT INTO processing_jobs (id,project_id,status,progress,current_step) VALUES (?,?,'queued',0,'Waiting for worker')").bind(jobId,id)
      ]);
      await env.TRANSCRIPT_QUEUE.send({ projectId:id,userId:user.id,mediaKey,fileName:file.name,model,language,timestamps });
      return json({ project: { id,title,status:"queued" } }, 202);
    } catch (error) {
      await env.MEDIA.delete(mediaKey).catch(()=>{});
      await env.DB.prepare("DELETE FROM projects WHERE id=? AND user_id=?").bind(id,user.id).run().catch(()=>{});
      return json({ error: error instanceof Error ? error.message : "Could not queue this transcription." }, 500);
    }
  }
  const match = path.match(/^\/api\/projects\/([a-f0-9-]+)$/i);
  if (match) {
    const projectId = match[1];
    const project = await env.DB.prepare("SELECT id,title,source_type,source_name,media_size,language,selected_model,status,error_message,created_at,updated_at FROM projects WHERE id=? AND user_id=?").bind(projectId,user.id).first<Project>();
    if (!project) return json({ error: "Project not found." }, 404);
    if (request.method === "GET") {
      const transcript = await env.DB.prepare("SELECT id,original_text,edited_text,language,summary,word_count FROM transcripts WHERE project_id=?").bind(projectId).first();
      const segments = transcript ? (await env.DB.prepare("SELECT id,start_time,end_time,text,speaker_label,sequence_number FROM transcript_segments WHERE transcript_id=? ORDER BY sequence_number").bind((transcript as {id:string}).id).all()).results : [];
      const job = await env.DB.prepare("SELECT status,progress,current_step,error_message FROM processing_jobs WHERE project_id=? ORDER BY created_at DESC LIMIT 1").bind(projectId).first();
      return json({ project, transcript, segments, job });
    }
    if (request.method === "PATCH") {
      const body = await request.json() as { title?: string; editedText?: string };
      if (typeof body.editedText === "string") {
        const transcript = await env.DB.prepare("SELECT id FROM transcripts WHERE project_id=?").bind(projectId).first<{id:string}>();
        if (!transcript) return json({ error: "Transcript is not ready yet." }, 409);
        const wordCount = body.editedText.trim() ? body.editedText.trim().split(/\s+/).length : 0;
        await env.DB.prepare("UPDATE transcripts SET edited_text=?,word_count=?,updated_at=datetime('now') WHERE id=?").bind(body.editedText.slice(0,1000000),wordCount,transcript.id).run();
      }
      if (typeof body.title === "string") await env.DB.prepare("UPDATE projects SET title=?,updated_at=datetime('now') WHERE id=? AND user_id=?").bind(cleanTitle(body.title),projectId,user.id).run();
      return json({ ok:true });
    }
    if (request.method === "DELETE") {
      const key = await env.DB.prepare("SELECT media_key FROM projects WHERE id=? AND user_id=?").bind(projectId,user.id).first<{media_key:string|null}>();
      if (key?.media_key) await env.MEDIA.delete(key.media_key);
      await env.DB.prepare("DELETE FROM projects WHERE id=? AND user_id=?").bind(projectId,user.id).run();
      return json({ ok:true });
    }
  }
  return json({ error: "Not found." }, 404);
}

async function processTranscript(message: TranscriptJob, env: Env) {
  const existing = await env.DB.prepare("SELECT status FROM projects WHERE id=? AND user_id=?").bind(message.projectId,message.userId).first<{status:string}>();
  if (existing?.status === "completed") return;
  await env.DB.prepare("UPDATE projects SET status='processing',updated_at=datetime('now') WHERE id=? AND user_id=?").bind(message.projectId,message.userId).run();
  await env.DB.prepare("UPDATE processing_jobs SET status='processing',progress=15,current_step='Sending audio to Groq Whisper',updated_at=datetime('now') WHERE project_id=?").bind(message.projectId).run();
  const object = await env.MEDIA.get(message.mediaKey);
  if (!object) throw new Error("Uploaded media was not found in R2.");
  const form = new FormData();
  form.append("file", new File([await object.arrayBuffer()], message.fileName, { type: object.httpMetadata?.contentType || "application/octet-stream" }));
  form.append("model", message.model);
  form.append("response_format", message.timestamps ? "verbose_json" : "json");
  if (message.language !== "auto") form.append("language", message.language);
  const response = await fetch("https://api.groq.com/openai/v1/audio/transcriptions", {
    method:"POST",headers:{Authorization:`Bearer ${env.GROQ_API_KEY}`},body:form,signal:AbortSignal.timeout(110000)
  });
  if (!response.ok) {
    const detail = (await response.text().catch(()=>"" )).slice(0,300);
    if (response.status === 429) throw new Error("Groq rate limit or free quota reached. Retry later.");
    throw new Error(`Groq transcription failed (${response.status}): ${detail}`);
  }
  const data = await response.json() as {text?:string;language?:string;duration?:number;segments?:Array<{start?:number;end?:number;text?:string}>};
  const text = String(data.text || "");
  const transcriptId = crypto.randomUUID();
  const wordCount = text.trim() ? text.trim().split(/\s+/).length : 0;
  await env.DB.prepare("INSERT INTO transcripts (id,project_id,original_text,edited_text,language,word_count) VALUES (?,?,?,?,?,?)").bind(transcriptId,message.projectId,text,text,data.language || message.language,wordCount).run();
  if (data.segments?.length) {
    const statements = data.segments.map((s,i)=>env.DB.prepare("INSERT INTO transcript_segments (id,transcript_id,start_time,end_time,text,sequence_number) VALUES (?,?,?,?,?,?)").bind(crypto.randomUUID(),transcriptId,Number(s.start||0),Number(s.end||0),String(s.text||"").trim(),i));
    for (let i=0;i<statements.length;i+=100) await env.DB.batch(statements.slice(i,i+100));
  }
  await env.DB.batch([
    env.DB.prepare("UPDATE projects SET status='completed',media_duration=?,language=?,error_message=NULL,updated_at=datetime('now') WHERE id=?").bind(data.duration || null,data.language || message.language,message.projectId),
    env.DB.prepare("UPDATE processing_jobs SET status='completed',progress=100,current_step='Transcript ready',error_message=NULL,updated_at=datetime('now') WHERE project_id=?").bind(message.projectId)
  ]);
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname.startsWith("/api/")) {
      try { return await handleApi(request,env); }
      catch (error) { return json({error:error instanceof Error ? error.message : "Unexpected server error."},500); }
    }
    return env.ASSETS.fetch(request);
  },
  async queue(batch: MessageBatch<TranscriptJob>, env: Env): Promise<void> {
    for (const message of batch.messages) {
      try {
        await processTranscript(message.body,env);
        message.ack();
      } catch (error) {
        const messageText = error instanceof Error ? error.message : "Processing failed.";
        const attempts = message.attempts || 1;
        if (attempts >= 3) {
          await env.DB.batch([
            env.DB.prepare("UPDATE projects SET status='failed',error_message=?,updated_at=datetime('now') WHERE id=?").bind(messageText.slice(0,500),message.body.projectId),
            env.DB.prepare("UPDATE processing_jobs SET status='failed',error_message=?,current_step='Failed',updated_at=datetime('now') WHERE project_id=?").bind(messageText.slice(0,500),message.body.projectId)
          ]);
          message.ack();
        } else message.retry({ delaySeconds: Math.min(60 * attempts,300) });
      }
    }
  }
};
