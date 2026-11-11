# Ghar Watch + Ghar Fit Log — Full Project Handover

Version: v1.0 final, compiled Nov 4, 2026.
Owner: Saurabh. Users: Saurabh and Priyanka (personal first, open source later).

**How to use this file in a new chat:** paste this whole file (or attach it) plus the attached `index.html` (current Ghar Fit Log source). Then say: "This is my project handover. Continue from Sprint 0 / the current step." Section 24 has a ready starter prompt.

Links to keep:
- Live plan doc (Claude Doc): https://claude.ai/artifact/S88jctjejNjHZqoNQTWzBV
- Ghar Fit Log web app (artifact): https://claude.ai/artifact/SyAKm2VzbDD6mE2ymW3PXE

---

## 1. One-paragraph summary

Turn a Samsung Galaxy Watch 4 into a lean analog watch plus a health tracker that works with an iPhone, at ₹0. A custom Wear OS app ("Ghar Watch", Kotlin) collects data from Health Services and the Samsung Health Sensor SDK (developer mode), stores it on the watch, and uploads compact batches over home Wi-Fi to a free Supabase project. The iPhone side is the existing web app "Ghar Fit Log" (a claude.ai artifact), which reads Supabase and adds food logging, plans, photos, Ask Claude, charts, a muscle body map and an exercise library. An old LG phone is used once to set the watch up, then switched off. Built for Saurabh and Priyanka first; generalised into a public open-source repo later.

---

## 2. People, devices, constraints

| Item | Detail |
|---|---|
| Saurabh | iPhone (main phone). Galaxy Watch 4. Lives in Bangalore; at home in Pilani, Rajasthan until Nov 28, 2026. WFH, work 2–10 pm IST. Preparing for GATE DA 2027 (usually early February; confirm date). Engineer comfortable with Python, data, embedded/DFT concepts |
| Priyanka | Android phone + Galaxy Watch 4. Lives in Bangalore. Office ~9 am–4/5 pm (2 days WFH). Her watch stays paired to her own Android phone and keeps Samsung Health; she also gets the Ghar Watch app |
| Spare phone | LG, Android 8. Enough for one-time watch setup (Samsung lists Galaxy Watch4 minimum as Android 6.0+, 1.5 GB RAM). Too old for current Samsung Health (6.27+ needs Android 10) and for Health Connect |
| Laptop | Used for Android Studio, ADB, scripts. No Mac needed |
| Home Wi-Fi | Router at home; the watch syncs through it |
| Budget | ₹0 target. Optional later upgrade: used Android 10+ phone (~₹3–5k) as a home hub (Option B, section 4) |
| Watch software | End of life. Fine for this project; app targets the Wear OS version already on the watch (check Settings > About watch) |
| Timeline | Build starts Nov 29, 2026 (after return to Bangalore). Core (Sprints 1–5) before GATE final prep; extras after GATE |

---

## 3. Final decisions (v1.0)

Priority order for every trade-off: **reliability > battery > accuracy > features > looks.**

1. Watch app in Kotlin (Wear OS, Compose for Wear OS). Health Services for passive data and workouts. Samsung Health Sensor SDK in Health Sensor Service developer mode for raw PPG, beat-to-beat intervals (IBI), SpO2, ECG and BIA.
2. No phone in Saurabh's daily path: watch uploads over home Wi-Fi to Supabase. LG used once for setup, then off and kept as a spare.
3. iPhone side is Ghar Fit Log (web app). No native iOS app, no Apple Health. ₹0.
4. Own algorithms only, from published methods. No Samsung code or algorithms copied.
5. Samsung Health stays on the watch until our numbers are validated against it (Sprint 6), then it gets disabled (Saurabh's watch only).
6. Core build (Sprints 1–5) finishes before GATE final prep; extras wait until after the exam.
7. **Personal first, general later.** Built for Saurabh (iPhone, watch on home Wi-Fi) and Priyanka (Android, watch paired to her phone, Samsung Health keeps running), with their own targets and programs. Hard-coded personal choices are fine until Sprint 8; the open-source phase turns them into settings.
8. Self-improving accuracy: a weekly job refits model parameters against personal ground truth (section 9).
9. All features free, no subscription.

### "Better than stock" targets

| Metric | Target |
|---|---|
| Battery | 36+ hours with all-day tracking, sleep and a 1-hour workout |
| Steps | Within 3% of Samsung Health |
| Workout calories | Within 10–15% of Samsung Health / treadmill, with more context (program, body parts) |
| Sleep start and wake | Within 15 minutes of reality on 5 test nights |
| Data loss | Zero: nothing deleted on the watch until the server confirms; no lost sleep nights |
| Sync delay | Under 1 hour while charging on Wi-Fi |

---

## 4. What was considered and rejected (and why)

| Idea | Verdict | Reason |
|---|---|---|
| Native iOS app writing to Apple Health | Rejected | Only native apps can write HealthKit. HealthKit generally needs the paid Apple Developer Program (~₹8,700/yr). Free Apple ID sideload (SideStore/AltStore) = re-sign every 7 days, 3-app limit, and HealthKit likely unavailable on free accounts |
| Native iOS app without Apple Health | Rejected | Possible at ₹0 (public GitHub repo → free macOS runners → unsigned IPA → SideStore), but adds nothing over the web app and Ask Claude would need a paid API key |
| iPhone pretending to be a Samsung phone ("mimic the companion") | Impossible | Watch ↔ phone link uses Bluetooth Classic + Google's encrypted Wear OS channel that depends on Google Play Services. iOS apps can't use Bluetooth Classic (Apple limits it to MFi accessories) and can't run Play Services |
| Galaxy Watch 4 pairing with iPhone at all | Impossible | Samsung dropped iOS support from Watch4 onwards |
| Reverse engineering / extracting Samsung algorithms | Rejected | Encrypted inside Samsung Health and sensor firmware; breaks Samsung's terms; IP-protected; unnecessary because published methods cover the plan |
| Custom sensor firmware | Impossible | Sensor hub firmware is signed by Samsung; bricking risk |
| Rooting the watch or LG | Rejected | Root trips Knox on Samsung devices and Samsung Health stops; Samsung Health database is encrypted; no tracking benefit |
| Direct BLE watch → iPhone bridge (Wear OS GATT server + iOS CoreBluetooth) | Rejected for now | Needs Mac/Xcode, unreliable iOS background BLE, uncertain BLE peripheral support on Watch 4; Wi-Fi + backend is simpler. Possible later as an offline extra |
| Using Samsung Health's own processed data (sleep stages, sleep score, stress, energy score) | Optional Option B | Needs Samsung Health on an Android 10+ phone + Samsung Health Data SDK developer mode (Settings > About Samsung Health > tap version 10+ times > Developer mode > Developer Mode for Data Read). LG is too old. Cost ~₹3–5k used phone; breaks "no phone" decision. Revisit after Sprint 6 if own sleep stages disappoint |
| Health Connect / Samsung Health export on the LG | Not possible | Health Connect needs Android 9+; current Samsung Health needs Android 10+ |
| iPhone notifications on the watch | Out of scope | Not possible natively; ANCS bridge is a fiddly side quest |
| Music, voice assistant, media | Out of scope | Battery and focus |
| Continuous all-night SpO2 | Out of scope | Sensor SDK measures SpO2 on demand |
| Recording audio on the watch (snore) | Rejected | Battery drain. Optional DeltaSleep on the LG at the bedside instead (section 21) |

---

## 5. Architecture

```
Galaxy Watch 4 — Ghar Watch app (Kotlin)
   ├─ Health Services: passive steps, HR, calories, floors, distance, "asleep" state; ExerciseClient for workouts
   ├─ Samsung Health Sensor SDK (developer mode): PPG green/IR/red, HR + IBI, SpO2, ECG, BIA
   ├─ Raw SensorManager: accelerometer (night actigraphy), gyroscope, barometer, light
   └─ Local database (Room) → batched uploads over home Wi-Fi (while charging, or every 1/3/6 h)
            ↓
Supabase (free tier): Postgres tables + row-level security + optional edge functions
            ↓
Ghar Fit Log on iPhone (claude.ai artifact web app; add to home screen)
   reads Supabase via the claude.ai Supabase connector (mcp capability)
   fallback: free dashboard on GitHub Pages reading Supabase directly, or weekly import by Claude
```

- Data flows one way over Wi-Fi. The watch never talks to the iPhone directly.
- Away from home (gym, mandir, travel), the watch stores data and uploads when back on Wi-Fi.
- **LG rules:** set up the watch once, then switch the LG off and keep it in a drawer. **Never unpair the watch from the LG and never factory reset the watch** (either wipes it and needs the LG again). **Turn watch Bluetooth off** after setup so it stops searching for the LG. A "phone disconnected" icon is expected.
- Priyanka: her watch stays paired to her Android phone (normal Samsung Health). Ghar Watch is installed on her watch over Wi-Fi ADB in Sprint 1 and uploads the same way.

---

## 6. Sensors, SDKs and how each is used

| Sensor | Access | Used for |
|---|---|---|
| Optical heart rate (PPG) | Health Services | All-day HR, workout HR and zones, resting HR, calorie formula, sleep HR dip |
| Accelerometer | Raw SensorManager (batched) + hardware step counter | Steps, sleep movement (actigraphy), restlessness, optional rep counting |
| Gyroscope | Raw SensorManager | Optional: better rep counting for gym sets |
| Barometer | Health Services floors + raw pressure | Floors climbed, mandir stair elevation gain |
| GPS | Health Services during workouts | Mandir ride distance, speed, route |
| Ambient light | Raw SensorManager | Sleep context (lights off), face brightness |
| ECG and BIA electrodes (back + side buttons) | Samsung Health Sensor SDK only | On-demand ECG and body composition |
| Raw PPG green/IR/red, IBI, SpO2 | Samsung Health Sensor SDK | HRV, deep/light sleep, SpO2, signal quality |

### Samsung Health Sensor SDK (v1.4.1 at time of research)
- Works on Galaxy Watch4 and later (Wear OS powered by Samsung). No emulator support.
- Data types listed: raw accelerometer, BIA, MF-BIA, ECG, EDA, heart rate with inter-beat interval, PPG (green, IR, red), skin temperature, SpO2, sweat loss. Skin temperature, EDA and sweat need newer watches than Watch 4.
- **Developer mode (watch):** Settings > Apps > Health Sensor Service (may show as "Health Platform") > tap the title about 10 times > Developer mode button > agree to Samsung Health Partner Service & SDK License > turn on.
- Without partner approval the SDK returns `SDK_POLICY ERROR`; developer mode lets your own differently-signed app work. Intended for testing; **distributing** an app that uses the SDK needs Samsung partner approval (app package name + SHA-256 signature registered). Fine for personal use.
- API value keys found in the API reference:
  - `HeartRateSet`: `IBI_LIST` (ms), `IBI_STATUS_LIST`
  - `PpgSet` (current class; `PpgGreenSet`/`PpgIrSet`/`PpgRedSet` deprecated): `PPG_GREEN`, `PPG_IR`, `PPG_RED`, `GREEN_STATUS`, `IR_STATUS`, `RED_STATUS`
  - `SpO2Set`: `SPO2` (%), `HEART_RATE`, `STATUS`
  - `BiaSet` (BIA_ON_DEMAND): `BODY_FAT_MASS`, `BODY_FAT_RATIO`, `FAT_FREE_MASS`, `FAT_FREE_RATIO`, `SKELETAL_MUSCLE_MASS`, `SKELETAL_MUSCLE_RATIO`, `TOTAL_BODY_WATER`, `BASAL_METABOLIC_RATE`, `PROGRESS`, `STATUS`, plus one impedance magnitude (ohms) and impedance degree
  - `MfBiaSet` (MF_BIA_ON_DEMAND): `BODY_IMPEDANCE_MAGNITUDE_5K/_10K/_50K/_250K`, `BODY_IMPEDANCE_PHASE_5K/_10K/_50K/_250K` — may need a newer watch; test on Watch 4
- The SDK `.aar` is proprietary: never commit it to a public repo.

### Health Services (Wear OS)
- Passive monitoring: steps, heart rate, calories, distance, floors (query `passiveMonitoringClient.capabilities` at runtime for the exact supported list).
- **Sleep state:** `UserActivityInfo` / `UserActivityState.USER_ACTIVITY_ASLEEP`. Needs `ACTIVITY_RECOGNITION` permission, `setShouldUserActivityInfoBeRequested(true)` on `PassiveListenerConfig`, override `onUserActivityInfoReceived()`. `stateChangeTime` may be in the past.
- Manifest: `PassiveListenerService` protected by `com.google.android.wearable.healthservices.permission.PASSIVE_DATA_BINDING`; `RECEIVE_BOOT_COMPLETED` receiver to re-register after reboot; body sensor permissions (+ background body sensor access).
- ExerciseClient for workouts (types like walking, biking, strength, yoga, HIIT; check whether DANCING is supported on Watch 4, otherwise use a generic type for Just Dance).
- Correct client getter: `HealthServices.getClient(context)` (one pasted snippet had the typo `HealthServices.get there(context)`).

### Raw PPG signal cleaning (own implementation of standard methods)
- 4th-order Butterworth band-pass 0.5–4 Hz on 25 Hz PPG.
- Kurtosis gating: keep 3-second windows with kurtosis about 2.8–5.2; reject the rest as motion artifacts (important for Just Dance and gym).
- Hardware FIFO batching (~3 s) so the CPU sleeps; reuse buffers to avoid GC pauses.
- Fall back to Health Services HR if skin contact is lost for 5+ minutes.
- Night PPG sampled in short windows (about 1 minute every 5–10 minutes) for HRV to protect battery.
- Ideas noted from NeuralPulse (no license, so none of its code is reused).

### Body composition (BIA)
- Show Samsung's computed values (via SDK) by default; store raw impedance magnitude and phase.
- Later test published BIA equations: resistance R = |Z| × cos(phase). Published equations mostly assume hand-to-foot; the watch is hand-to-hand, so calibrate against Samsung's values before trusting own numbers.
- Measure weekly, same time, fasted, after the toilet.

---

## 7. Algorithms

| Metric | Method |
|---|---|
| Steps, floors | Hardware step counter and barometer via Health Services (no reimplementation) |
| Resting calories | Mifflin-St Jeor BMR, spread across the day |
| Workout calories | Keytel HR formula (age, weight, sex, HR); MET × weight × hours fallback when HR drops out (Just Dance ≈ MET 4–6) |
| Sleep timing | Health Services "asleep" state for bedtime and wake time |
| Sleep quality | Cole-Kripke wrist actigraphy on 1-minute movement counts + overnight HR dip → awakenings, restlessness score |
| Resting HR | Lowest 5-minute average HR during sleep |
| Fitness estimate | VO2max ≈ 15.3 × (max HR ÷ resting HR) (Uth); trend only |
| Mandir climb | Barometric elevation gain during the ride workout |
| HRV / stress | RMSSD from Sensor SDK IBI (night windows + on-demand); stress/recovery = HRV vs personal baseline |
| Training load | TRIMP from time in HR zones; 7-day vs 28-day load ratio |
| Readiness (0–100) | HRV vs baseline, resting HR vs baseline, sleep, recent load |
| Early warning | Resting HR up and HRV down vs baseline for 2+ days → "go easy today" |

**Keytel formula (kcal per minute)** — HR in bpm, W = weight kg, A = age years:
- Men: (−55.0969 + 0.6309·HR + 0.1988·W + 0.2017·A) / 4.184
- Women: (−20.4022 + 0.4472·HR − 0.1263·W + 0.074·A) / 4.184

**Mifflin-St Jeor BMR (kcal/day)** — W kg, H cm, A years:
- Men: 10W + 6.25H − 5A + 5
- Women: 10W + 6.25H − 5A − 161

**Blended workout calories:**
kcal = w · Keytel(HR) + (1 − w) · MET · W · t(hours)
w is high when HR is steady/clean, low when the reading is noisy (from kurtosis gate / Health Services accuracy flags).

**Calibration for "beating Samsung":** personal resting HR (from sleep), personal max HR (from a hard session), weight synced from Ghar Fit Log. Validate by running Samsung Health side by side for 2 weeks and against treadmill readouts; target agreement within 10–15% before disabling Samsung Health.

---

## 8. Sleep stages and SpO2 (open-source route)

| Feature | Method | Expected accuracy |
|---|---|---|
| Sleep vs wake | Walch et al., SLEEP 2019: wrist acceleration + HR; open code (GitHub `ojwalch/sleep_classifiers`) and PhysioNet "sleep-accel" data | ~90% of 30-s epochs; only ~60% of wake epochs caught |
| Wake / NREM / REM | Same model, three-class | ~72%; good for trends |
| Deep vs light | Needs beat-to-beat HR (HRV) | Planned: HRV from Sensor SDK IBI + movement + HR |
| SpO2 | Red/IR ratio-of-ratios | Use the Sensor SDK's built-in SpO2 (on-demand); own red/IR algorithm only as an experiment (would need a ~₹500–1,000 fingertip oximeter to calibrate) |

Fine-tune against Samsung Health for 2–4 weeks before disabling it. Realistic expectation: our sleep timing can match or beat Samsung; stages probably stay behind Samsung without its models.

---

## 9. Self-improving accuracy

The watch runs small models with tunable parameters; a weekly job refits them against personal ground truth and pushes new parameters on the next sync. ₹0.

1. Scheduled job (GitHub Actions or laptop Python) reads Supabase weekly.
2. Compares predictions with ground truth and refits parameters.
3. Saves a new model version; deploys only if it beats the current one on past data, else rolls back automatically.
4. Watch downloads the parameter file (JSON) on its next Wi-Fi sync.

| Metric | Ground truth | What gets tuned |
|---|---|---|
| Daily calories | Weight trend vs logged food (1 kg ≈ 7,700 kcal) over 2–3 weeks | Personal correction factor on total burn |
| Workout calories | Treadmill readouts; Samsung's on-watch figure during validation | HR-to-calorie curve, MET per program segment |
| Sleep timing | 10-second morning check-in on a watch tile (bedtime, wake, rested 1–5) | Actigraphy and HR-dip thresholds |
| Sleep stages | Samsung Health stage minutes read off the watch for 2–4 weeks, entered in Ghar Fit Log | Personal stage model on top of Walch |
| Resting HR, HRV, stress | Rolling 30-day personal baseline | All scores relative to personal normal |
| Body composition | Samsung's on-watch BIA values | Own impedance-based equation |

**Guardrails:** at least 14 nights before sleep changes, 3 weeks of weigh-ins before calorie changes; one-tap "skip day" for illness, festivals, travel; all data stays in the personal Supabase. Ghar Fit Log shows an accuracy panel: error per metric over time and the current model version.

---

## 10. Program engine and stretching

Every workout is a program file (JSON). The watch uses it to time segments, weight calories by intensity, estimate body-part focus, and pick matching stretches. Programs are created in Ghar Fit Log (simple form) and downloaded by the watch on its next Wi-Fi sync.

### Program file format
```json
{
  "id": "jd2-fitness-10",
  "name": "Just Dance 2 – Fitness 10 min",
  "type": "dance",
  "owner": "saurabh",
  "segments": [
    {"name": "Warm-up song", "min": 3, "met": 4.0, "focus": ["legs", "shoulders"]},
    {"name": "Cardio songs", "min": 5, "met": 6.0, "focus": ["legs", "glutes", "core", "arms"]},
    {"name": "Cool-down", "min": 2, "met": 3.0, "focus": ["full body"]}
  ],
  "warmup": ["ankle circles", "leg swings", "arm circles"],
  "stretch_after": ["calves", "quads", "hip flexors", "shoulders"]
}
```
The Just Dance 2 segments are placeholders: fill in the real fitness-mode structure once from the game.

### Gym program format (sets/reps)
```json
{
  "id": "gym-upper",
  "name": "Gym – Upper",
  "type": "strength",
  "exercises": [
    {"name": "Chest press / push-ups", "sets": 3, "reps": "10-12", "rest_s": 75, "primary": ["chest"], "secondary": ["triceps", "front delts"]},
    {"name": "Lat pulldown", "sets": 3, "reps": "10-12", "rest_s": 75, "primary": ["lats"], "secondary": ["biceps"]},
    {"name": "Seated row", "sets": 3, "reps": "10-12", "rest_s": 75, "primary": ["upper back"], "secondary": ["biceps"]},
    {"name": "Shoulder press", "sets": 3, "reps": "10-12", "rest_s": 75, "primary": ["shoulders"], "secondary": ["triceps"]},
    {"name": "Bicep curl", "sets": 3, "reps": "10-12", "rest_s": 60, "primary": ["biceps"]},
    {"name": "Tricep pushdown", "sets": 3, "reps": "10-12", "rest_s": 60, "primary": ["triceps"]},
    {"name": "Plank", "sets": 3, "hold_s": "30-45", "rest_s": 45, "primary": ["core"]}
  ],
  "stretch_after": ["chest", "lats", "shoulders", "triceps"]
}
```
Saurabh's other gym programs (from his current plan):
- **Gym – Lower:** leg press / goblet squat, Romanian deadlift, walking lunges, glute bridge, calf raises, McGill Big 3 (bird-dog, dead bug, side plank). 3 × 10–12, rest 60–90 s.
- **Gym – Full body (light legs):** goblet squat (light), push-ups, lat pulldown, glute bridge, farmer walk, dead bug.
- Plus 20 min incline walk after weights.

Priyanka's programs (from her plan):
- **Full body A:** leg press or goblet squat, lat pulldown, dumbbell chest press, arms (band or 1–2 kg curls), glute bridge.
- **Full body B:** Romanian deadlift (light), seated row, incline push-ups on bench, arms (band or 1–2 kg overhead triceps), short walking lunges.
- **Core (Tue/Thu):** dead bug, bird-dog, side plank (knees), glute bridge, cat-cow; with 30–40 min incline treadmill.
- **Arm progression:** week 1 band/1 kg 2 × 8 (stop 2 reps before it burns badly); week 2: 2 × 10; week 3: 3 × 10 or 2 kg; week 4: 3 × 12.
- Just Dance 30 min, 4–5 days/week.

Other programs: **Mandir ride** (15 km cycle + ~500 stairs up and down; GPS + barometric climb), **Walk**, **Treadmill**, **Yoga**.

### During / after a workout
- During: segment or exercise name, set counter, rest timer with buzz, time left, live HR and zone, calories, buzz at each segment change.
- After: total calories, time per HR zone, body-part split.
- Body-part split for gym = exercise → muscles (primary 1.0 × sets, secondary 0.5 × sets). For cardio/dance = program focus areas weighted by segment intensity and HR. A wrist watch can't sense muscles directly; cardio splits are estimates.

### Stretching
- Dynamic warm-up before (3–5 min) from focus areas.
- Static stretches after, from `stretch_after`, 30 s each, buzz to switch sides, short text cue (e.g. "Hamstring – left, 30 s").
- Daily 10-min mobility program: cat-cow, child's pose, chin tucks, wall angels, doorway chest stretch, McGill Big 3.
- 2-min desk stretch (neck, shoulders, hips) with the hourly move reminder.
- Public repo later: shareable program library.

---

## 11. Phone app: Ghar Fit Log (current state + planned)

### Current state (already built and live)
- Artifact: https://claude.ai/artifact/SyAKm2VzbDD6mE2ymW3PXE — title "Ghar Fit Log". Source file attached as `index.html` (~43 KB, single file, vanilla JS).
- Capabilities declared: `db`, `user`, `sample` (with images), `assets`.
- Profile switch: Saurabh / Priyanka (remembered in localStorage key `fitlog.pid`).
- Tabs: **Today** (calories vs target, protein, steps, water glasses ±250/500 ml, morning weight, waist, steps, sleep hours, workouts with minutes + kcal from the watch, food list by meal, day notes), **Food** (meal chips Breakfast/Lunch/Snacks/Dinner, search ~60 Indian foods with kcal/protein, servings multiplier, "Describe it or snap it" Claude estimate from text or plate photo returning JSON items, custom item), **Progress** (latest weight, change since start, 30-day weight and waist line charts with goal line, 14-day calorie bars, photo uploads tagged Progress/Meal/Report/Other), **Ask Claude** (chat with plan + last 7 days of logs as context, suggestion chips per person), **Plan** (editable phase, kcal, protein, water, steps, start/goal weight, goal date, diet, notes).
- Database layout (artifact db):
  - `people/{pid}` — profile: name, phase, kcal, protein, water (ml), steps, diet, startWeight, goalWeight, goalDate, notes
  - `people/{pid}/days/{YYYY-MM-DD}` — date, foods[] {id, name, qtyLabel, kcal, protein, meal, at, est?}, water, steps, workouts[] {id, type, min, kcal}, weight, waist, sleep, notes, updated
  - `people/{pid}/photos/{auto}` — asset, url, tag, date, at
  - pid values: `saurabh`, `priyanka`
- Seeded data: both profiles with targets and notes; Saurabh 2026-10-28 weight 110; Priyanka 2026-10-31 weight 81.4, waist ~34 in (hips ~37 in in notes).
- Current targets: Saurabh 1,800 kcal, 120 g protein, 3,000 ml water, 10,000 steps, start 110 kg, goal 102 kg by 2026-11-28, phase "Home in Pilani, Rajasthan until Nov 28 (WFH 2-10 pm)". Priyanka 1,500 kcal, 95 g protein, 2,750 ml, 8,000 steps, start 81.4 kg, goal 78 kg by 2026-11-29. (Her health notes are stored in her profile notes in the app.)
- Sharing: Priyanka must be invited **by email as an Editor** from the app's Share menu to save logs and upload photos.
- Install: iPhone Safari > Share > Add to Home Screen; Android Chrome > ⋮ > Add to Home screen.
- Known limits: the artifact can't fetch external servers (CSP) — Supabase must be reached through the claude.ai Supabase connector (`mcp` capability) or a separate dashboard; watch data is entered manually until Sprint 5.

### Planned additions
- Meal templates (one-tap usual meals) and location presets (one-tap Rajasthan (Pilani) ↔ Bangalore targets) — can ship before the watch work.
- Program builder form (creates program JSON for the watch).
- Morning briefing: sleep, readiness, suggested intensity (hard / normal / easy), calories left.
- Sunday Claude report: weekly trends + the one thing to fix, after weigh-in.
- Accuracy panel (section 9).
- Label entry during validation (Samsung's stage minutes, workout kcal).
- Couple view (opt-in shared weekly progress, joint goals); Priyanka's cycle and supplement tracking.
- **Body map:** every workout (including Just Dance) gets a body render. v1 = 2D front/back heat map (Sprint 7); v2 = 3D with three.js using muscle meshes exported from Z-Anatomy (CC BY-SA 4.0) simplified to ~20 muscle groups as one glTF file (derived model stays CC BY-SA with attribution). Per workout: muscles shaded by load (none → light → medium → heavy). Weekly (Sunday): shaded by weekly volume with a "target more" list for muscles under goal (e.g. 6–10 hard sets/week while cutting).
- **Exercise library (MuscleWiki-style, own content):** free-exercise-db (GitHub `yuhonas/free-exercise-db`, 873 exercises, Unlicense; fields: id, name, level beginner/intermediate/expert, force, mechanic, equipment, primary/secondary muscles, instructions, category, images). Tap a muscle → exercises filtered by Level 1/2/3 (easy/medium/hard), no equipment (home), "what I own" (Saurabh: push-up bars, 5 kg dumbbell, skipping rope, resistance band, Magic Back Stretcher), gym. One tap adds an exercise to a watch program. MuscleWiki is a design reference only; nothing copied.

---

## 12. Battery and performance

Target: at least 36 hours per charge with all-day tracking, sleep tracking and one 1-hour workout. Charge during evening work hours.

**App rules**
- Let Health Services and the low-power sensor hub do passive tracking; never poll sensors in a loop.
- Raw accelerometer only at night, low rate, hardware batching (FIFO), 1-minute counts.
- GPS only inside the Mandir ride workout.
- Wi-Fi uploads only while charging (or every 1/3/6 h, selectable; once-a-day fallback), compressed batches, no per-sample network calls.
- No always-on screen updates from the app; tiles refresh at most every 15 minutes.
- Night PPG in short windows only.

**Watch setup**
- Analog face in Watch Face Format (Samsung's free Watch Face Studio): no code, low power, minimal second hand in ambient mode. Readiness number on the face from Sprint 3.
- Always-on display off; raise-to-wake on; brightness auto.
- Debloat over ADB with `adb shell pm disable-user --user 0 <package>` (reversible with `adb shell pm enable <package>`), only after checking each package: Bixby, Samsung Pay, Google Assistant, Play Store auto-updates, Spotify/YouTube Music, Find My, weather and news tiles, Samsung Health (only after Sprint 6 validation, Saurabh's watch).
- **Never disable:** system UI, Health Services, **Health Sensor Service** (needed by the Sensor SDK), Wi-Fi, settings.
- Bluetooth off after setup (Saurabh's watch).
- Keep a text list of every disabled package with its re-enable command (a mistaken disable can cause a boot loop or break settings).

---

## 13. Quality-of-life features (final list)

Picked from: what wearable users want most (YouGov global survey: long battery 65%, fitness/health tracking 44%, display 39%, notifications 38%); Galaxy Watch 4 owners' complaints (idle drain ~1.3–1.8%/h, no "lite" mode, fixed HR intervals, lost sleep data, accidental screen wakes at night, 10-minute auto-workout delay, auto-walks without GPS); what people value in Whoop/Oura/Garmin (recovery/readiness, strain, HRV trends, no subscription).

| Feature | What it does | Sprint | Battery |
|---|---|---|---|
| Power modes | Full / Balanced / Essential (time, steps, HR, workouts only); HR interval: continuous, 10, 30 or 60 min | 1 | Saves |
| Adjustable sync | Upload while charging only, or every 1 / 3 / 6 hours | 1 | Saves |
| Never-lose sleep data | Sleep data saved every 15 minutes during the night | 1 | None |
| Morning sleep check-in tile | Bedtime, wake, rested 1–5 (ground truth for learning) | 1 | None |
| Weekly backup | Free scheduled job exports Supabase to CSV in a private GitHub repo | 1 | None |
| Study focus timer | Vibration-only timer for GATE blocks (e.g. 50/10); study minutes logged | 2 | None |
| Routine buzzes | Water tile +1 glass with 2 pm catch-up buzz, 4:30 shake, 10 pm kitchen closed, hourly move + desk stretch | 2 | None |
| Interval timer + quick auto-detect | Custom intervals for stairs/HIIT; auto-detect walk/ride after 1, 5 or 10 min with GPS for rides | 2 | Low |
| Auto bedtime mode | Once asleep: screen and tap-to-wake off, button only | 3 | Saves |
| Smart alarm | Silent buzz in lightest sleep within 20 min before set time (6:45) | 3 | Low |
| Bedtime nudges | 11:00 wind down / phone away; 11:30 sleep | 3 | None |
| Readiness score | Morning 0–100 on the analog face | 3 | None |
| Early warning flag | RHR up + HRV down 2+ days → go easy | 3 | None |
| Auto battery saver | Below 20%: Essential mode automatically | 4 | Saves |
| Morning briefing (Ghar Fit Log) | Sleep, readiness, suggested intensity, calories left | 5 | – |
| Meal templates + location presets (Ghar Fit Log) | One-tap meals; Rajasthan (Pilani) ↔ Bangalore targets | 5 (can ship earlier) | – |
| Training load + HRV status | TRIMP strain, 7-day vs 28-day ratio, 7-day rolling HRV | 6 | None |
| Sunday Claude report | Weekly trends + one fix | 6 | – |
| Couple view | Shared weekly progress, joint goals; Priyanka's cycle + supplement tracking | 7 | – |
| Rep counter, energy gauge | Push-up/squat reps from wrist motion; Body Battery-style energy | 7 | Low |

Deliberately left out: phone notifications (impossible with iPhone), music, voice assistant, always-on mic, fall detection (no phone/LTE to call anyone), streak badges.

---

## 14. Fast-track schedule

Core build (Sprints 1–5) in about 6 weeks after the Nov 28 return, at about 6 hours per weekend. QoL features ship inside their sprints (section 13). GATE is usually early February; confirm and pause after Sprint 5 if final prep needs the time.

| Sprint | Dates | Deliverable | Done when |
|---|---|---|---|
| 0. Setup | One evening this week (~2 h) | Watch paired once with the LG; developer options, ADB, Wi-Fi debugging on; Health Sensor Service developer mode on; LG off; watch Bluetooth off | `adb connect` works over Wi-Fi |
| Prep (light) | Nov 9–28, Sunday evenings, ≤2 h/week | Android Studio + Wear OS emulator; Supabase project and tables; analog face in Watch Face Studio; read Health Services and Sensor SDK samples (FlowFit as reference); note the real Just Dance 2 fitness structure | Sample app runs on the watch and logs PPG + intervals |
| 1. Core tracking + sync | Nov 29 – Dec 6 | Passive steps, HR, calories, floors; morning sleep check-in tile; power modes; adjustable sync; crash-safe local store; idempotent batched uploads while charging; device battery log; weekly backup job; install on Priyanka's watch | 48 hours of data in Supabase, no gaps, no duplicates |
| 2. Programs | Dec 7 – Dec 20 | Program engine (JSON from Ghar Fit Log); Just Dance 2, Gym Upper/Lower/Full, Mandir ride, Walk, Treadmill, Yoga; blended calories; body-part summary; warm-up, post-workout stretches, daily mobility; study timer; routine buzzes; interval timer + auto-detect | 5 real sessions logged; calories within 15% of Samsung |
| 3. Sleep + HRV | Dec 21 – Dec 27 | Asleep state + actigraphy + night HRV windows; nightly summary; resting HR; auto bedtime mode; smart alarm; bedtime nudges; readiness; early warning | 5 nights with start/wake within 15 min |
| 4. Lean watch | Dec 28 – Jan 3 | Analog face, tile (steps, kcal, water +1), move reminder with desk stretch, debloat list applied, auto battery saver | 36+ hours per charge for 3 days |
| 5. Ghar Fit Log link | Jan 4 – Jan 10 | Ghar Fit Log reads Supabase: steps, workouts, sleep, HRV, resting HR fill in automatically; morning briefing; meal templates; location presets | A day's data appears with no typing |
| 6. Validate + stages | After GATE | 2–4 week comparison vs Samsung Health (labels entered in Ghar Fit Log); Walch sleep-stage model + HRV for deep/light; weekly self-learning job and accuracy panel; training load; Sunday report; then disable Samsung Health | Targets table met |
| 7. Extras | After GATE | On-demand SpO2, ECG, BIA screens; 2D then 3D body map; exercise library; couple view; rep counter; energy gauge; optional DeltaSleep snore data | Each feature works for 1 week |
| 8. Open source | After 2–4 weeks of daily use | Public repo, Supabase template, GitHub Pages dashboard, README with one-time Android setup; Sensor SDK as optional module | Someone else sets it up from the README alone |

**Reliability rules (all sprints):** local database write before anything else; every record has a unique ID so re-uploads never duplicate (upsert on ID); delete from the watch only after the server confirms; retry with exponential backoff; services restart after reboot (boot receiver); record raw sensor sessions once and replay them to test algorithms without wearing the watch.

---

## 15. Supabase data model

Row-level security on every table; the watch writes with its own key (per-device), Ghar Fit Log reads.

| Table | Key columns | Written |
|---|---|---|
| `samples` | id, person, ts, type (hr, steps, kcal, floors, ibi, spo2…), value, device_id | Each upload, batched per minute |
| `workouts` | id, person, start, end, program_id, avg_hr, max_hr, kcal, distance_m, climb_m, sets (json), zones (json), body_split (json) | End of each workout |
| `sleep_sessions` | id, person, start, end, total_min, awakenings, restlessness, resting_hr, hrv_rmssd, stages (json) | Each morning |
| `daily_summary` | person, date, steps, kcal_active, kcal_total, floors, resting_hr, sleep_min, hrv, readiness | Recomputed after each upload |
| `devices` | device_id, person, battery, last_upload, app_version | Each upload |
| `programs` | id, person, json, updated_at | From Ghar Fit Log; watch downloads |
| `model_params` | person, version, json, created_at, metrics (json), active | Weekly learning job |
| `labels` | person, date, kind (sleep_stage, workout_kcal, checkin), json | Ghar Fit Log / watch tile |

Ghar Fit Log mostly needs `daily_summary`, `workouts`, `sleep_sessions`; `samples` is for charts and debugging. Trim raw samples after 90 days to stay inside the free tier. Free projects pause after about a week with no activity; daily uploads keep it active.

Starter SQL (adjust as needed):
```sql
create table devices (device_id text primary key, person text not null, battery int, last_upload timestamptz, app_version text);
create table samples (id text primary key, person text not null, device_id text, ts timestamptz not null, type text not null, value double precision);
create index on samples (person, ts);
create table workouts (id text primary key, person text not null, start timestamptz, "end" timestamptz, program_id text, avg_hr int, max_hr int, kcal int, distance_m int, climb_m int, sets jsonb, zones jsonb, body_split jsonb);
create table sleep_sessions (id text primary key, person text not null, start timestamptz, "end" timestamptz, total_min int, awakenings int, restlessness real, resting_hr int, hrv_rmssd real, stages jsonb);
create table daily_summary (person text not null, date date not null, steps int, kcal_active int, kcal_total int, floors int, resting_hr int, sleep_min int, hrv real, readiness int, primary key (person, date));
create table programs (id text primary key, person text not null, json jsonb not null, updated_at timestamptz default now());
create table model_params (person text not null, version int not null, json jsonb not null, metrics jsonb, active boolean default false, created_at timestamptz default now(), primary key (person, version));
create table labels (id bigserial primary key, person text not null, date date not null, kind text not null, json jsonb);
-- enable RLS on all tables, then add policies per role/key
```

---

## 16. Sprint 0 — step by step (do this first)

1. **Pair the watch with the LG** using Galaxy Wearable (if the Play Store version won't install on Android 8, use an older Galaxy Wearable + Watch4 plugin APK). If the watch is already set up with some Android phone, skip to step 2.
2. On the watch, enable **Developer options**: Settings > About watch > Software information > tap **Software version** about 5–7 times.
3. Settings > Developer options: turn on **ADB debugging** and **Wireless debugging / Debug over Wi-Fi**. Connect the watch to home Wi-Fi.
4. On the laptop (Android platform-tools installed):
   - Wear OS 3+: in Wireless debugging tap **Pair new device** → note IP:port and code → `adb pair <ip>:<pair-port> <code>` → then `adb connect <ip>:<port>`.
   - Check with `adb devices`.
5. Turn on **Health Sensor Service developer mode** (section 6).
6. Optional quick test: install a minimal app that reads `PpgSet` and `HeartRateSet` IBI and logs them (`adb logcat`).
7. **Turn off watch Bluetooth.** Switch off the LG and store it. **Do not unpair and do not factory reset.**
8. Record: watch Wear OS / One UI Watch version, whether developer mode worked, whether multi-frequency BIA appears.

---

## 17. Tools (all free)

Android Studio (Kotlin, Compose for Wear OS), Android platform-tools (ADB), Samsung Watch Face Studio, Samsung Health Sensor SDK (download from Samsung developer site), Supabase free project, GitHub (private repo for code; Actions for backup and learning jobs; public repo later), Python on laptop for analysis.

---

## 18. Risks and fallbacks

| Risk | Effect | Fallback |
|---|---|---|
| LG (Android 8) can't install Galaxy Wearable for first setup | Can't enable developer mode | Older Galaxy Wearable APK; or borrow any Android 9+ phone for one evening |
| Wear OS kills the overnight sensor service | Gaps in sleep data | Foreground service with ongoing notification; rely on Health Services asleep state |
| "Asleep" state unreliable on Watch 4 | Wrong bedtime/wake | Actigraphy alone decides sleep windows |
| Battery under 36 hours | Daily charging stress | Lower night sampling, uploads only while charging, trim debloat further |
| Supabase free project pauses | Sync stops | Daily uploads keep it active; app retries and keeps local data |
| Supabase connector doesn't fit Ghar Fit Log | No auto-fill | Free dashboard on GitHub Pages reading Supabase, or weekly import by Claude |
| Watch software end of life | No Samsung fixes | Fine; target the installed Wear OS version |
| Sensor SDK proprietary, partner approval for distribution | Public repo can't ship it | Optional module; users add their own SDK download; partner request if the repo grows. Reference code: FlowFit (MIT). NeuralPulse has no license → view only |
| ExerciseType DANCING not supported | Just Dance type missing | Use a generic workout type; program file carries the real details |

---

## 19. Open questions

- [ ] Does the LG complete Galaxy Wearable setup? (Sprint 0)
- [ ] Does Sensor SDK developer mode work on this Watch 4's software version? (Sprint 0)
- [ ] Does ExerciseType include dancing on the Watch 4?
- [ ] Does the Watch 4 return multi-frequency impedance, or only single-frequency BIA?
- [ ] Exact GATE DA 2027 date, to confirm where the build pauses
- [x] Decided: Priyanka's watch gets Ghar Watch too, alongside her Samsung Health (install over Wi-Fi ADB in Sprint 1)
- [ ] Later: Option B hub phone (Android 10+) for Samsung's own sleep stages, only if own stages disappoint after Sprint 6

---

## 20. Open-source release (Sprint 8)

- Public repo: watch app APK + ADB install guide; Supabase template (SQL + setup guide, one project per user so data stays theirs); free iPhone web dashboard on GitHub Pages (Ghar Fit Log stays personal); README stating the one-time Android setup up front.
- License: Apache-2.0 (simple, allows commercial use) or GPL-3.0 (keeps forks open). Z-Anatomy-derived 3D model stays CC BY-SA 4.0 with attribution; code can keep its own license.
- No "Samsung" or "Galaxy" in the project name ("works with Galaxy Watch 4" in the README is fine). No Samsung code, SDK binaries or APKs in the repo.
- Disclaimer: not a medical device, accuracy not guaranteed.
- Privacy: no analytics, no tracking; data only in the user's own Supabase.
- Works on other Wear OS 3+ watches for Health Services features; Sensor SDK features only on Galaxy Watch4+.
- Optional Play Store listing: one-time $25 developer fee.
- Check existing projects first (e.g. Gadgetbridge is Android-only and doesn't support Wear OS Galaxy watches).

---

## 21. Reference repos and sources (with verdicts)

| Source | Verdict |
|---|---|
| Samsung Health Sensor SDK overview — https://developer.samsung.com/health/sensor/overview.html | Core dependency |
| Sensor SDK developer mode — https://developer.samsung.com/health/sensor/guide/developer-mode.html | Sprint 0 steps |
| Sensor SDK app creation process — https://developer.samsung.com/health/sensor/process.html | Partner approval needed only for distribution |
| Sensor SDK API index — https://developer.samsung.com/health/sensor/api-reference/index-all.html | Value keys listed in section 6 |
| Health Services passive monitoring — https://developer.android.com/training/wearables/health-services/passive | Sleep state + passive data |
| ExerciseClient — https://developer.android.com/health-and-fitness/health-services/active-data | Workouts |
| ExerciseType reference — https://developer.android.com/reference/kotlin/androidx/health/services/client/data/ExerciseType | Check DANCING |
| Walch et al., SLEEP 2019 — https://pmc.ncbi.nlm.nih.gov/articles/PMC6930135 ; data https://www.physionet.org/content/sleep-accel/ | Sleep model base |
| FlowFit — https://github.com/KpG782/flowfit/ (MIT, Flutter + Kotlin, Supabase, reads HR + IBI via Sensor SDK; HRV not built) | Best reference; code reusable with credit |
| NeuralPulse — https://github.com/Ashutosh0x/neuralpulse-wear (no license, 29 commits, Wear OS 5+, phone relay, unbacked "clinical-grade" claims) | Ideas only (DSP, FIFO, replay harness); no code reuse |
| DeltaSleep — https://github.com/ntufar/DeltaSleep (MIT, Android 8+, microphone-only sleep/snore, v0.1, no validation) | Optional snore tracking on the LG at the bedside; not the main stager |
| Open Wearables — https://github.com/the-momentum/open-wearables | Skip (needs Android phone; validated scores in paid tiers) |
| samsung-health-sdk (Python) — https://github.com/Devasy/samsung-health-sdk | Only for parsing Samsung Health exports during validation (needs a phone with Samsung Health) |
| Samsung Health Research Stack — https://news.samsung.com/global/samsung-electronics-unveils-samsung-health-research-stack | Reference only; heavy |
| Z-Anatomy — https://github.com/Z-Anatomy/Models-of-human-anatomy (CC BY-SA 4.0, Blender) | 3D muscle model source |
| Free Exercise DB — https://apis.io/providers/free-exercise-db/ (github.com/yuhonas/free-exercise-db, 873 exercises, Unlicense) | Exercise library |
| Samsung Health Data SDK developer mode — https://developer.samsung.com/health/data/guide/developer-mode.html | Option B only |
| Samsung Health 6.27 drops Android 9 and older — https://www.sammobile.com/news/samsung-health-version-6-27-drops-support-android-9-older/ | Why LG can't run Samsung Health |
| Galaxy Watch compatible devices — https://www.samsung.com/au/support/mobile-devices/devices-and-operating-systems-that-work-with-samsung-galaxy-watch/ | Android 6.0+ / 1.5 GB RAM; iOS not supported |
| Apple HealthKit entitlement — https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.developer.healthkit | Why Apple Health was dropped |
| GitHub Actions billing — https://docs.github.com/en/billing/concepts/product-billing/github-actions | Public repos free; private ~$12/month credit, macOS ≈ $0.062/min |
| YouGov wearable features — https://business.yougov.com/content/43985-global-what-features-do-consumers-rate-important-w | QoL research |
| Galaxy Watch 4 bugs and requests — https://eu.community.samsung.com/t5/wearables/galaxy-watch-4-bugs-and-requests-after-2-months-use/td-p/4239916 | QoL research |
| Whoop vs Oura vs Garmin — https://athletedata.health/guides/whoop-vs-oura-vs-garmin | QoL research |
| Supabase MCP server guide — https://www.verdent.ai/guides/supabase-mcp-server | Connector route for Ghar Fit Log |

---

## 22. Related artifacts made in the same chat (keep copies)

- Ghar Fit Log web app (link above) + source `index.html`.
- Ghar Watch plan doc (link above; same content as this handover, sections may be ordered differently).
- Saurabh's 8 kg cut plan PDF (Oct 28 – Nov 28): `Saurabh_Cut_Plan_Nov2026.pdf`.
- Priyanka's plan doc: https://claude.ai/artifact/9XwTKyb7YnwFo91g6NdDdD and PDF `Priyanka_Plan_Nov2026.pdf` (updated with her Nov 1 report).
- Priyanka intake questionnaire doc: https://claude.ai/artifact/VuPjabm8VZjAb4oy6yV6pf
Download the PDFs before deleting the chat.

---

## 23. Out of scope (summary)

iPhone notifications on the watch; Apple Health and native iOS app; continuous all-night SpO2; custom sensor firmware; music, voice assistant, media; fall detection; audio recording on the watch.

---

## 24. Starter prompt for a new chat

> I'm continuing my project "Ghar Watch + Ghar Fit Log". The attached handover file is the full spec and all decisions (v1.0, Nov 4 2026) — treat it as final unless I change something. The attached index.html is the current Ghar Fit Log artifact source (https://claude.ai/artifact/SyAKm2VzbDD6mE2ymW3PXE). Users: me (Saurabh, iPhone + Galaxy Watch 4, watch syncs over home Wi-Fi to Supabase) and Priyanka (Android + Galaxy Watch 4). Priorities: reliability > battery > accuracy > features > looks; ₹0 budget; personal first, open source later. Current step: Sprint 0 (pair the watch once with my LG Android 8 phone, enable developer options, ADB, Wi-Fi debugging, and Health Sensor Service developer mode). Start by helping me with Sprint 0, then write the Sprint 1 Kotlin watch app and the Supabase setup.
