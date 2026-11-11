# Ghar Watch — Sprint 1 (core tracking + sync)

**Done when:** 48 hours of your data in Supabase, no gaps, no duplicates (`tools/check_supabase.sql` queries 5 + 6).

## What it does
| Part | How |
|---|---|
| All-day tracking | Health Services passive listener: daily steps, calories, floors, distance, heart rate, asleep/awake/exercise state. Runs while the app is closed. |
| Sleep timing | Health Services "asleep" state → sleep session rows. Open session saved every 30 min, so a night is never lost. |
| Local store | SQLite outbox (WAL, crash-safe). Every row written locally first. |
| Upload | WorkManager over Wi-Fi: while charging (hourly) + daily fallback, or every 1/3/6 h. Asks Wear OS for Wi-Fi only while uploading. Batches of 300, upsert by id, deletes locally only after the server confirms. Rejected rows are kept in a "dead" table, never silently dropped. |
| Login | Your laptop logs in and pushes a refresh token over ADB. The watch renews its own token; your password never touches the watch. |
| Power modes | Full (everything) / Essential (steps, kcal, sleep). Auto saver: Essential below 20%, back at 40% or on charger. |
| Check-in | Morning screen (5–11 am): bed/wake time (pre-filled from the watch), rested 1–5 → `labels` table (ground truth for learning). |
| Battery log | Every 30 min → `samples` type `battery`. |
| Reboot-safe | Boot receiver re-registers tracking and schedules. |

Not in Sprint 1 (by plan): tiles (Sprint 4), workouts/programs (Sprint 2), actigraphy + night HRV (Sprint 3).
HR measuring frequency is controlled by **Samsung Health → Heart rate → Measurement** on the watch (set it to *Every 10 min* or *Continuous*).

## 1. Create the project (Android Studio)
New Project → Wear OS → **Empty Wear App** → Name `Ghar Watch` · Package `dev.ghar.watch` · Location `C:\dev\ghar-watch` · Min SDK API 30 → Finish. Wait for sync.

## 2. Copy the code
1. Close Android Studio's editor tabs.
2. Copy the zip's `app\src\main\` folder over `C:\dev\ghar-watch\app\src\main\` → **Replace** when asked.
   (Your template's `presentation/theme` folder stays; it's unused and harmless.)
3. Copy `.gitignore` to `C:\dev\ghar-watch\` (replace) and the `tools` folder to `C:\dev\ghar-watch\tools`.
4. Paste `build-gradle-additions.txt` lines into `dependencies { }` of `app/build.gradle.kts` → **Sync Now**.

## 3. Run
Watch connected (`adb devices`) → device `samsung SM-R870` → **Run ▶**.
On the watch: tap **1. Allow sensors** → Allow → tap **2. Allow all the time** → Allow.

## 4. Link to Supabase
```
cd C:\dev\ghar-watch\tools
copy ghar.local.example.json ghar.local.json
notepad ghar.local.json          # paste full publishable key + your login email, save
powershell -ExecutionPolicy Bypass -File .\push_config.ps1
```
Expect: `Watch linked.` The watch screen should show `Linked · saurabh · gw4-xxxx`.

## 5. Check it works (10 min later)
- Watch: **Sync now** → "0 waiting · last HH:MM", no red error.
- Supabase SQL Editor → run `tools/check_supabase.sql`.

## 6. Leave it for 48 h
Wear it all day and night, charge in the evening. Then run queries 5 and 6. Done = HR rows every hour, 0 duplicates.

## Troubleshooting
| Watch shows | Fix |
|---|---|
| `NOT LINKED` | Run push_config.ps1 |
| `LOGIN: refresh rejected` | Run push_config.ps1 again |
| `no Wi-Fi` | Watch Wi-Fi on + saved home network; retries automatically |
| `needs Activity permission` / `PERMISSION LOST` | Open app, tap the Allow chips |
| `error: SecurityException …` in tracking line | Tap Settings → Restart tracking after allowing both permissions |
| Build error | Paste the **first red error** in the chat |

---
# Sprint 1.5 — UI foundation (v1.1)

**What changed:** design system (true black, Inter font, one colour per metric), rings home screen
(Move = active kcal estimate, Steps, Floors), cards for HR / sleep / distance / sync, swipe-right to go back,
bezel scrolling, background data reads (UI redraws only when a number changes), release build with R8 +
profile installer, Settings for weight and goals. Tracking and sync code unchanged — data and link are kept.

## Update steps
1. Copy the zip's `app\src\main\` over `C:\dev\ghar-watch\app\src\main\` → Replace all.
   (Adds `res\font\` with 4 Inter files and `presentation\ui\`.)
2. Follow `build-gradle-additions.txt` (2 new dependencies + release block) → Sync Now.
3. Build Variants → **release** → Run ▶.
4. On the watch: Settings → set your **weight** (used for the Move ring).

## Measure smoothness (numbers, not feel)
Scroll the home screen up/down for ~10 s, then:
```
adb shell dumpsys gfxinfo dev.ghar.watch | findstr /i "Total frames Janky 50th 90th 95th 99th"
```
Target on the release build: **Janky < 5 %** and **90th percentile ≤ 16 ms** (= steady 60 fps).
Reset counters between tests: `adb shell dumpsys gfxinfo dev.ghar.watch reset`

Font: Inter, SIL Open Font License 1.1 (see INTER_FONT_LICENSE.txt).

## v1.2 (Nov 11)
- Heart rate stored as 1 row per minute (`hr_1m`: avg + min/max/n) instead of ~1 per second (~60× less data/battery).
- New rings: Move (active kcal) · Exercise (min) · Active hours. Floors moved to the Steps line.
- Detail screens with 24-hour bar charts: Move, Exercise, Active hours/Steps, Heart.
- Total burned kcal = active + resting (Mifflin). Server now gets `kcal_active` and `kcal_total` correctly.
- Water: log +250/+500/undo, reminders every 90 min 09–22 (via the existing 30-min heartbeat, no extra alarms).
- Body: weekly weight/waist/neck → US Navy body-fat estimate, plus optional Samsung BIA fat % and muscle.
- One design language (see Theme.kt header). Sync screen: "Retry rejected rows".
- Supabase: run `tools/06_v1_2.sql` BEFORE installing.

## v1.3 (Nov 11)
- Workouts: picker (only types this watch supports) → live screen (timer, HR + zone, kcal, km/steps, zone bar,
  pause/resume, double-tap end) → summary. Foreground service + ongoing-activity icon: keeps recording with the
  screen off; survives the app being killed (re-attaches). Saved to `workouts` (avg/max HR, kcal, distance, steps,
  zones). Workout time counts fully toward the Exercise ring.
- Rings tile: swipe left from the watch face → add "Ghar rings". Refreshes every 15 min and on new data.
- Gradle/proguard additions: V1_3_GRADLE_AND_PROGUARD.txt. No SQL needed.

## v1.4 (Nov 11)
- Morning HRV: 60-s reading (Samsung Sensor SDK HR + IBI → RMSSD, artefact-filtered, ≥ 30 clean beats).
  Home shows "Morning HRV" 05–11 until done; HRV tile with 7-day baseline; result vs baseline (±15 % bands).
  Saved as daily_summary.hrv + sample `hrv_rmssd` (meta: hr, beats). Energy score uses this from ~day 14.
- Setup: V1_4_GRADLE.txt (add the .aar). No SQL needed.
