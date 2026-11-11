-- =====================================================================
-- Run AFTER creating your login in Supabase → Authentication → Users → Add user.
-- Replace the email below with the one you used, then Run.
-- =====================================================================
insert into public.people (person, user_id, name)
select 'saurabh', id, 'Saurabh'
from auth.users
where email = 'REPLACE_WITH_YOUR_LOGIN_EMAIL'
on conflict (person) do update set user_id = excluded.user_id;

-- Check: should return one row with your user_id filled in.
select person, user_id, name from public.people;
