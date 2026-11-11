-- =====================================================================
-- OPTIONAL: run the 90-day trim automatically every Sunday 03:00 IST.
-- First enable the extension: Supabase → Database → Extensions → search "pg_cron" → Enable.
-- Then run this once.
-- =====================================================================
select cron.schedule(
  'ghar-trim-samples',
  '30 21 * * 6',                      -- cron is UTC: Sat 21:30 UTC = Sun 03:00 IST
  $$select public.trim_old_samples(90)$$
);

-- To check:  select * from cron.job;
-- To remove: select cron.unschedule('ghar-trim-samples');
