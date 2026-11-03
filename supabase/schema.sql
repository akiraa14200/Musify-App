create extension if not exists pgcrypto;

create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  display_name text,
  avatar_url text,
  created_at timestamptz not null default now()
);

create table if not exists public.projects (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  title text not null,
  source_type text not null check (source_type in ('upload','recording','youtube_caption')),
  source_url text,
  media_duration numeric,
  language text,
  selected_model text,
  status text not null default 'queued' check (status in ('queued','uploading','processing','completed','failed','cancelled')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.transcripts (
  id uuid primary key default gen_random_uuid(),
  project_id uuid not null unique references public.projects(id) on delete cascade,
  original_text text not null default '',
  edited_text text not null default '',
  summary text,
  word_count integer not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.speakers (
  id uuid primary key default gen_random_uuid(),
  transcript_id uuid not null references public.transcripts(id) on delete cascade,
  speaker_name text not null,
  speaker_label text not null
);

create table if not exists public.transcript_segments (
  id uuid primary key default gen_random_uuid(),
  transcript_id uuid not null references public.transcripts(id) on delete cascade,
  start_time numeric not null default 0,
  end_time numeric not null default 0,
  text text not null default '',
  speaker_id uuid references public.speakers(id) on delete set null,
  sequence_number integer not null,
  unique (transcript_id, sequence_number),
  check (end_time >= start_time)
);

create table if not exists public.processing_jobs (
  id uuid primary key default gen_random_uuid(),
  project_id uuid not null references public.projects(id) on delete cascade,
  status text not null default 'queued',
  progress integer not null default 0 check (progress between 0 and 100),
  current_step text,
  error_message text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists projects_user_updated_idx on public.projects(user_id, updated_at desc);
create index if not exists transcript_segments_order_idx on public.transcript_segments(transcript_id, sequence_number);
create index if not exists processing_jobs_project_idx on public.processing_jobs(project_id, created_at desc);

alter table public.profiles enable row level security;
alter table public.projects enable row level security;
alter table public.transcripts enable row level security;
alter table public.speakers enable row level security;
alter table public.transcript_segments enable row level security;
alter table public.processing_jobs enable row level security;

create policy "profiles_select_own" on public.profiles for select using (auth.uid() = id);
create policy "profiles_update_own" on public.profiles for update using (auth.uid() = id) with check (auth.uid() = id);
create policy "projects_owner_all" on public.projects for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "transcripts_owner_all" on public.transcripts for all using (exists (select 1 from public.projects p where p.id = project_id and p.user_id = auth.uid())) with check (exists (select 1 from public.projects p where p.id = project_id and p.user_id = auth.uid()));
create policy "speakers_owner_all" on public.speakers for all using (exists (select 1 from public.transcripts t join public.projects p on p.id = t.project_id where t.id = transcript_id and p.user_id = auth.uid())) with check (exists (select 1 from public.transcripts t join public.projects p on p.id = t.project_id where t.id = transcript_id and p.user_id = auth.uid()));
create policy "segments_owner_all" on public.transcript_segments for all using (exists (select 1 from public.transcripts t join public.projects p on p.id = t.project_id where t.id = transcript_id and p.user_id = auth.uid())) with check (exists (select 1 from public.transcripts t join public.projects p on p.id = t.project_id where t.id = transcript_id and p.user_id = auth.uid()));
create policy "jobs_owner_all" on public.processing_jobs for all using (exists (select 1 from public.projects p where p.id = project_id and p.user_id = auth.uid())) with check (exists (select 1 from public.projects p where p.id = project_id and p.user_id = auth.uid()));
