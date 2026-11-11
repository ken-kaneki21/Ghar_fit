# Ghar Watch — Supabase setup (~30 min, ₹0)

Files in this folder, run in order:
| File | When |
|---|---|
| `01_schema.sql` | Step 3 — creates 9 tables + security rules (safe to re-run) |
| `02_add_person.sql` | Step 4 — links your login to person `saurabh` |
| `03_optional_trim_schedule.sql` | Optional — auto-deletes raw samples older than 90 days |
| `04_test_upload.ps1` | Step 5 — proves login + upload work, exactly like the watch will |
| `05_cleanup_test.sql` | Step 6 — removes the test rows |

Tested locally before delivery: 16/16 checks passed (own-data only, no cross-person writes,
no deletes from clients, anonymous blocked, re-uploads never duplicate).

## 1. Create the project
1. supabase.com → Sign in (GitHub login is fine) → **New project**.
2. Name `ghar-watch` · strong DB password (save it in your password manager) · Region **South Asia (Mumbai)** · Free plan → Create.
3. Wait ~2 min until the dashboard loads.

## 2. Save two values (Project Settings → API / API Keys)
| Value | Looks like | Where it goes |
|---|---|---|
| Project URL | `https://abcd1234.supabase.co` | Watch app + test script |
| Publishable / anon key | `sb_publishable_...` or `eyJ...` | Watch app + test script |

**Never** put the `service_role` / secret key on the watch or in any app. It bypasses all security.
Keep both values in a private note for now.

## 3. Run the schema
SQL Editor → **New query** → paste all of `01_schema.sql` → **Run**. Expect "Success. No rows returned".
Table Editor should now show: daily_summary, devices, labels, model_params, people, programs, samples, sleep_sessions, workouts.

## 4. Create your login and link it
1. **Authentication → Users → Add user → Create new user**.
   Email: any email you control (e.g. a `+watch` alias) · strong password · tick **Auto Confirm User**.
2. SQL Editor → paste `02_add_person.sql` → replace `REPLACE_WITH_YOUR_LOGIN_EMAIL` → Run.
   The last result must show `saurabh` with a `user_id` filled in.

## 5. Test from your laptop (PowerShell)
Open `04_test_upload.ps1` in Notepad, fill in the 4 values at the top, save, then:
```
cd C:\dev\ghar-supabase
powershell -ExecutionPolicy Bypass -File .\04_test_upload.ps1
```
Expected output ends with `ALL 4 CHECKS PASSED`.

## 6. Clean up
SQL Editor → run `05_cleanup_test.sql`.

## Notes
- **Free projects pause after ~7 days with no activity.** Until the watch uploads daily (Sprint 1), it may pause.
  If it does: dashboard → **Restore project** (1 click, data kept). Or create the project closer to Nov 29.
- Storage budget: ~7–10k sample rows/day ≈ 100 MB per 90 days. Free tier is 500 MB. The 90-day trim keeps it safe.
- Adding Priyanka later = one new login + one row in `people`. No schema change.
