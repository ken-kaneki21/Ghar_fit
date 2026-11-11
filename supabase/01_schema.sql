-- =====================================================================
-- Ghar Watch — Supabase schema v1 (Sprint 1)
-- Run once in Supabase → SQL Editor → New query → paste → Run.
-- Safe to re-run: every statement uses IF NOT EXISTS / OR REPLACE.
--
-- Design rules (from the handover):
--  * Every row has a client-made unique id → re-uploads upsert, never duplicate.
--  * Row-level security on every table: a login only sees/writes its own person.
--  * No DELETE from clients. Watch deletes local data only after the server confirms.
--  * `person` column everywhere so Priyanka can be added later without schema changes.
-- =====================================================================

-- ---------- People: maps a Supabase login to a person id ----------
create table if not exists public.people (
  person      text primary key check (person ~ '^[a-z][a-z0-9_]{1,30}$'),
  user_id     uuid unique references auth.users(id) on delete set null,
  name        text not null,
  created_at  timestamptz not null default now()
);

-- Returns the person id of the logged-in user (null if not mapped).
create or replace function public.my_person()
returns text
language sql stable security definer
set search_path = public
as $$
  select person from public.people where user_id = auth.uid()
$$;

-- ---------- Devices ----------
create table if not exists public.devices (
  device_id    text primary key,
  person       text not null references public.people(person),
  model        text,
  os_build     text,
  app_version  text,
  battery      int check (battery between 0 and 100),
  last_upload  timestamptz,
  updated_at   timestamptz not null default now()
);

-- ---------- Raw-ish samples (per-minute aggregates, HR, IBI windows…) ----------
create table if not exists public.samples (
  id           text primary key,                 -- e.g. "<device>:<type>:<epoch_ms>"
  person       text not null references public.people(person),
  device_id    text references public.devices(device_id),
  ts           timestamptz not null,
  type         text not null check (length(type) between 1 and 32),  -- hr, steps, kcal, floors, ibi, spo2, battery…
  value        double precision,
  meta         jsonb,                            -- optional: status flags, accuracy
  uploaded_at  timestamptz not null default now()
);
create index if not exists samples_person_ts on public.samples (person, ts);
create index if not exists samples_person_type_ts on public.samples (person, type, ts);

-- ---------- Workouts ----------
create table if not exists public.workouts (
  id           text primary key,
  person       text not null references public.people(person),
  device_id    text references public.devices(device_id),
  start_ts     timestamptz not null,
  end_ts       timestamptz,
  exercise_type text,                            -- Health Services type, e.g. DANCING, STRENGTH_TRAINING
  program_id   text,
  avg_hr       int, max_hr int, kcal int,
  distance_m   int, climb_m int, steps int,
  sets         jsonb, zones jsonb, body_split jsonb,
  uploaded_at  timestamptz not null default now(),
  check (end_ts is null or end_ts >= start_ts)
);
create index if not exists workouts_person_start on public.workouts (person, start_ts);

-- ---------- Sleep sessions ----------
create table if not exists public.sleep_sessions (
  id           text primary key,
  person       text not null references public.people(person),
  device_id    text references public.devices(device_id),
  start_ts     timestamptz not null,
  end_ts       timestamptz,
  total_min    int, awakenings int, restlessness real,
  resting_hr   int, hrv_rmssd real,
  stages       jsonb,
  uploaded_at  timestamptz not null default now(),
  check (end_ts is null or end_ts >= start_ts)
);
create index if not exists sleep_person_start on public.sleep_sessions (person, start_ts);

-- ---------- Daily summary (one row per person per day) ----------
create table if not exists public.daily_summary (
  person       text not null references public.people(person),
  date         date not null,
  steps int, kcal_active int, kcal_total int, floors int,
  distance_m int, resting_hr int, sleep_min int, hrv real, readiness int,
  updated_at   timestamptz not null default now(),
  primary key (person, date)
);

-- ---------- Programs (written by Ghar Fit Log, downloaded by the watch) ----------
create table if not exists public.programs (
  id           text primary key,
  person       text not null references public.people(person),
  json         jsonb not null,
  updated_at   timestamptz not null default now()
);

-- ---------- Model parameters (weekly self-learning job) ----------
create table if not exists public.model_params (
  person       text not null references public.people(person),
  version      int  not null,
  json         jsonb not null,
  metrics      jsonb,
  active       boolean not null default false,
  created_at   timestamptz not null default now(),
  primary key (person, version)
);
-- At most one active version per person.
create unique index if not exists model_params_one_active on public.model_params (person) where active;

-- ---------- Labels (ground truth: morning check-in, Samsung stage minutes…) ----------
create table if not exists public.labels (
  id           text primary key,                 -- client-made, so re-sends don't duplicate
  person       text not null references public.people(person),
  date         date not null,
  kind         text not null check (kind in ('checkin','sleep_stage','workout_kcal','weight','other')),
  json         jsonb not null,
  created_at   timestamptz not null default now()
);
create index if not exists labels_person_date on public.labels (person, date);

-- ---------- updated_at triggers ----------
create or replace function public.touch_updated_at()
returns trigger language plpgsql as $$
begin new.updated_at := now(); return new; end $$;

do $$
declare t text;
begin
  foreach t in array array['devices','daily_summary','programs'] loop
    execute format('drop trigger if exists %I_touch on public.%I', t, t);
    execute format('create trigger %I_touch before update on public.%I for each row execute function public.touch_updated_at()', t, t);
  end loop;
end $$;

-- =====================================================================
-- Row-level security
-- =====================================================================
alter table public.people         enable row level security;
alter table public.devices        enable row level security;
alter table public.samples        enable row level security;
alter table public.workouts       enable row level security;
alter table public.sleep_sessions enable row level security;
alter table public.daily_summary  enable row level security;
alter table public.programs       enable row level security;
alter table public.model_params   enable row level security;
alter table public.labels         enable row level security;

-- people: read your own row only. No client writes (rows are added by you in the SQL editor).
drop policy if exists people_select on public.people;
create policy people_select on public.people for select to authenticated
  using (user_id = auth.uid());

-- Own-person select / insert / update on every data table (upsert needs insert + update).
do $$
declare t text;
begin
  foreach t in array array['devices','samples','workouts','sleep_sessions','daily_summary','programs','labels'] loop
    execute format('drop policy if exists %I_select on public.%I', t, t);
    execute format('drop policy if exists %I_insert on public.%I', t, t);
    execute format('drop policy if exists %I_update on public.%I', t, t);
    execute format('create policy %I_select on public.%I for select to authenticated using (person = public.my_person())', t, t);
    execute format('create policy %I_insert on public.%I for insert to authenticated with check (person = public.my_person())', t, t);
    execute format('create policy %I_update on public.%I for update to authenticated using (person = public.my_person()) with check (person = public.my_person())', t, t);
  end loop;
end $$;

-- model_params: clients may only READ (written by the weekly job using the service key).
drop policy if exists model_params_select on public.model_params;
create policy model_params_select on public.model_params for select to authenticated
  using (person = public.my_person());

-- Anonymous (not logged in) gets nothing: no policies for role anon = no access.
revoke all on all tables in schema public from anon;
grant select, insert, update on public.devices, public.samples, public.workouts, public.sleep_sessions,
  public.daily_summary, public.programs, public.labels to authenticated;
grant select on public.people, public.model_params to authenticated;
grant execute on function public.my_person() to authenticated;

-- =====================================================================
-- Housekeeping: keep raw samples for 90 days (free tier = 500 MB)
-- =====================================================================
create or replace function public.trim_old_samples(keep_days int default 90)
returns int language plpgsql security definer set search_path = public as $$
declare n int;
begin
  delete from public.samples where ts < now() - make_interval(days => keep_days);
  get diagnostics n = row_count;
  return n;
end $$;
revoke execute on function public.trim_old_samples(int) from public, anon, authenticated;
