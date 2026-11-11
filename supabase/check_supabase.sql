-- Paste in Supabase → SQL Editor to see what the watch has uploaded.

-- 1) Device heartbeat: last upload time + battery
select device_id, model, app_version, battery, last_upload from public.devices;

-- 2) Today's summary
select * from public.daily_summary order by date desc limit 7;

-- 3) Rows per type in the last 24 h
select type, count(*) as rows, min(ts) as first, max(ts) as last
from public.samples
where ts > now() - interval '24 hours'
group by type order by type;

-- 4) Sleep sessions
select start_ts, end_ts, total_min from public.sleep_sessions order by start_ts desc limit 10;

-- 5) Sprint 1 "done" check: hourly coverage of HR over the last 48 h (gaps = hours with 0 rows)
select date_trunc('hour', ts) as hour, count(*) as hr_rows
from public.samples
where type = 'hr' and ts > now() - interval '48 hours'
group by 1 order by 1;

-- 6) Duplicate check (must return 0 rows)
select id, count(*) from public.samples group by id having count(*) > 1;

-- 7) v1.2: heart rate is now 1 row per minute (type hr_1m). Expect ≤ 60 per hour while worn.
select date_trunc('hour', ts) as hour, count(*) as minutes, round(avg(value)) as avg_bpm,
       min((meta->>'min')::int) as low, max((meta->>'max')::int) as high
from public.samples
where type = 'hr_1m' and ts > now() - interval '48 hours'
group by 1 order by 1;

-- 8) v1.2: today's derived metrics
select date, steps, kcal_active, kcal_total, exercise_min, active_hours, water_ml, hourly->'steps' as hourly_steps
from public.daily_summary order by date desc limit 3;

-- 9) v1.2: body measurements
select date, json from public.labels where kind = 'body_comp' order by date desc;
