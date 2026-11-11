# Ghar Watch + Ghar Fit Log: Handover v1.4 (current state, 11 Nov 2026)

Read this first, then `Ghar_Watch_Full_Handover_v1.0.md` for the original spec and decisions. Where they differ, this file wins.

## 1. Status in one line
Watch app **v1.4.2** runs on Saurabh's Galaxy Watch 4 (SM-R870), uploads to Supabase over Wi-Fi, and the iPhone web app **Ghar Fit Log** reads it through the claude.ai Supabase connector. Sprint 0 and 1 are done; parts of Sprints 2, 3 and 5 are done early (workouts, HRV, Fit Log link). Priyanka's watch is **out of scope for now**.

## 2. What works (tested on the watch, 11 Nov)
| Area | State |
|---|---|
| Passive tracking | Steps, active kcal (SM-R870 reports active kcal as `CALORIES_DAILY`), floors, distance, HR, sleep state. Counter-reset safe across re-registration. |
| Heart rate | Samsung Health HR set to **Continuous**. Stored as **1 row per minute** (`samples.type='hr_1m'`, value = avg, meta = min/max/n), ~60× less data than raw. |
| Rings (Apple-style) | Move (active kcal, goal 600), Exercise (min, goal 30), Active hours (≥250 steps in an hour, goal 12). Exercise minute = HR ≥ 60% of max (≈115 bpm) or cadence ≥ 100 steps/min, or any workout minute. |
| Detail screens | 24-h bars for Move/Exercise/Active hours/Steps; Heart: per-minute line (5-min points, MAX tag), hourly bars, low–high/avg. |
| Workouts | Health Services ExerciseClient in a foreground service (type `health`) + ongoing-activity icon. Picker shows only supported types (Walking, Running, Strength, HIIT, Football, Skipping, Dancing, Yoga…). Live: timer, HR + zone, kcal, km/steps, zone bar, pause/resume, double-tap end. Saved to `workouts` (avg/max HR, kcal, distance, steps, zones z1–z5 seconds). Recovers if the app is killed. Verified: 4-min walk saved, Exercise ring +5. |
| HRV | 60-s reading via **Samsung Health Sensor SDK 1.4.1** (`HEART_RATE_CONTINUOUS`, IBI). RMSSD with status filter + 20% artefact filter, needs ≥30 clean beats. Saved to `daily_summary.hrv` + `samples.hrv_rmssd`. Baseline = last 7 **morning (04–11:59)** readings, shown after 3. First test: 24.4 ms (post-walk, not baseline). |
| Water | +250/+500/undo; reminders every ~90–120 min 09:00–22:00 via the 30-min heartbeat (no extra alarms); skipped while asleep/charging/goal met. Goal 3.5 L. |
| Body | Weekly: weight, waist, neck → US Navy body-fat %; optional Samsung BIA fat % and muscle. Saved as `labels.kind='body_comp'`. |
| Tile | "Ghar rings" tile (rings drawn as a bitmap because Samsung's tile renderer recolours protolayout arcs). Samsung's picker doesn't list it reliably: add with `adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation add-tile --ecn component dev.ghar.watch/.tile.RingsTile`. |
| Sync | Outbox (SQLite WAL) → PostgREST upserts, refresh-token auth, Wi-Fi only. Default: while charging + daily fallback; Settings offers 1/3/6 h. |
| UI | One design language (see top of `Theme.kt`): true black, #1C1C1E surfaces, colour = metric (Move #FA114F, Exercise #92E82A, Hours #1EEAEF, Heart #FF453A, Sleep #7D7AFF, Water #64D2FF, Body #FFD60A), Inter font, type scale 40/20/16/13, 3 templates (Summary / Detail / List), rounded rect = info, capsule = action. Release build (R8) is smooth. |
| Fit Log (iPhone) | Saurabh's Today: Morning briefing (Go hard / Normal / Take it easy from sleep + HRV vs 7-day), watch metrics grid, steps-by-hour, HR line, watch workouts with zone bar; steps/water auto-merged; Progress: 30-day steps, exercise, HRV, body entries; Ask Claude gets 7 days of watch data. New: **Usual meals** (save a logged meal, one-tap re-add) and **Plan presets** (e.g. Pilani / Bangalore). |

## 3. Where things live
| Thing | Location |
|---|---|
| Watch app project | Laptop `C:\dev\ghar-watch` (package `dev.ghar.watch`), Android Studio, **release** variant. Also `watch/` in the GitHub repo. |
| Samsung SDK | `app/libs/samsung-health-sensor-api-1.4.1.aar`, dependency `implementation(files("libs/samsung-health-sensor-api-1.4.1.aar"))`. **Never commit** (licence). |
| Extra Gradle deps (v1.3) | `androidx.wear:wear-ongoing:1.0.0`, `androidx.wear.tiles:tiles:1.4.1`, `androidx.wear.protolayout:protolayout:1.2.1`; WorkManager **2.10.1**. |
| ProGuard keep rules | WorkManager/Room, Samsung SDK, Health Services (+ platform client), `dev.ghar.watch.health/tile/workout`, protolayout/tiles proto. |
| Supabase | Project `zwkmboddrxvezkephvda` (Mumbai). Person `saurabh` ↔ user_id `129e3d58-…`. Migrations in `supabase/` 01–07, run in order. 06 = v1.2 columns (`exercise_min, active_hours, water_ml, hourly`), labels kinds `body_comp, water`. 07 = function hardening. |
| Device | `gw4-61b456d8`. Config pushed by `tools/push_config.ps1` (refresh token only; password never on the watch). |
| Fit Log | Artifact https://claude.ai/artifact/SyAKm2VzbDD6mE2ymW3PXE. Capabilities: db, user, sample(images), assets, **mcp → Supabase `execute_sql`** (one fixed read-only SELECT, owner-only). Source in `fitlog/index.html`. |
| GitHub | Private `ken-kaneki21/Ghar_fit` (folders `watch/`, `supabase/`, `fitlog/`, `docs/`). Goes public only in Sprint 8, after scrubbing personal data. |

## 4. Rules that still apply
Reliability > battery > accuracy > features > looks. ₹0. Never put the service_role key in any app. Never factory-reset or unpair the watch. Never commit the .aar, `ghar.local.json`, `04_test_upload.ps1`, `local.properties`.

## 5. Known issues / watch-outs
- Samsung tile picker doesn't list "Ghar rings"; use the ADB add-tile command. Photos of the tile look washed out (camera exposure); judge by eye.
- Continuous HR battery cost is **not measured yet**: get a full-day battery % first. Auto saver drops to Essential (no HR) below 20%.
- Data reaches the phone only when the watch syncs (charging / Sync now / chosen interval).
- Day 1 (11 Nov) bars are distorted: steps before the v1.2 install landed in one hour.
- `SDK_POLICY_ERROR` / "Sensor service unavailable" → `adb shell am force-stop com.samsung.android.service.health`.
- Kotlin gotcha seen: `val bump = { refresh++ }` infers `() -> Int`; declare `() -> Unit`.

## 6. Next steps (in order)
1. **Next 2–3 days of data:** wear 24 h, sleep with it, morning check-in + HRV + weekly Body. Report: end-of-day battery %, queries #7/#8 in `check_supabase.sql`, Samsung vs Ghar steps/kcal.
2. Battery tuning based on (1): keep Continuous HR or switch to 10-min.
3. **Sprint 2 Programs:** program JSON (Gym upper/lower/full, Just Dance, walk/treadmill, yoga) built in Fit Log, downloaded by the watch; segment timer, rep counts (Samsung SDK supports squat/lunge etc.), blended kcal, stretches.
4. **Sleep:** stages model (Walch + HRV) after 14 nights; resting HR from overnight `hr_1m`.
5. **Energy score** (0–100: sleep 30, consistency 10, HRV 25, resting HR 20, load 10, feel 5) after ~14 days of morning HRV.
6. Morning briefing + Sunday report in Fit Log once baselines exist; validation vs Samsung Health for 2 weeks, then disable Samsung tracking.

## 7. Starter prompt for a new chat
> I'm continuing "Ghar Watch + Ghar Fit Log". Attached: `Ghar_Watch_Handover_v1.4.md` (current state, wins on conflicts) and `Ghar_Watch_Full_Handover_v1.0.md` (original spec). Watch app is v1.4.2 on my Galaxy Watch 4; Supabase project zwkmboddrxvezkephvda; Fit Log artifact reads it via the Supabase connector. Priorities: reliability > battery > accuracy > features > looks; ₹0. Start from section 6 "Next steps".
