-- Removes the rows created by 04_test_upload.ps1. Run in Supabase → SQL Editor.
delete from public.samples where device_id = 'test-laptop';
delete from public.devices where device_id = 'test-laptop';
select count(*) as samples_left from public.samples;
