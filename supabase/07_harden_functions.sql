-- Applied 2026-11-11 (Supabase security advisors). Safe to re-run.
alter function public.touch_updated_at() set search_path = '';
revoke execute on function public.my_person() from public, anon;
grant execute on function public.my_person() to authenticated;   -- RLS policies need it; returns only the caller's own person
