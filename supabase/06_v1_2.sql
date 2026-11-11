-- Ghar Watch v1.2 — run ONCE in Supabase → SQL Editor BEFORE installing v1.2 on the watch.
-- Safe to re-run. Adds columns only; nothing is deleted or changed.

alter table public.daily_summary
  add column if not exists exercise_min int,
  add column if not exists active_hours int,
  add column if not exists water_ml     int,
  add column if not exists hourly       jsonb;   -- {steps:[24], kcal:[24], exmin:[24], hr_avg:[24]}

-- Allow body-composition and water entries in labels.
alter table public.labels drop constraint if exists labels_kind_check;
alter table public.labels add constraint labels_kind_check
  check (kind in ('checkin','sleep_stage','workout_kcal','weight','body_comp','water','other'));

-- Make the API see the new columns immediately.
notify pgrst, 'reload schema';

-- Check: should list the 4 new columns.
select column_name from information_schema.columns
where table_schema = 'public' and table_name = 'daily_summary'
  and column_name in ('exercise_min','active_hours','water_ml','hourly');
