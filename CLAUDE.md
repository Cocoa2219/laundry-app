# CLAUDE.md

## Compose/CMP work: load the compose skill first; it picks the path and the files to read.

## Project

A laundry drying predictor for Android. The user says what they're drying and where; the app predicts **when to hang it, when it will be dry, and whether today is worth it**, using hourly weather data.

Prediction = **physics baseline × learned correction**.
- Physics gives a reasonable answer from day one with no data.
- An on-device regression learns how the user's drying spot differs from the physics, using "Did it dry?" feedback.

All data stays on the device. No accounts, no backend.

## Tech stack

- Kotlin Multiplatform + Compose Multiplatform
- UI: Material 3 Expressive (`MaterialExpressiveTheme`, expressive components and motion; opt in to `ExperimentalMaterial3ExpressiveApi`)
- Targets: `androidApp`, `shared` (with a `jvm` target so logic tests run fast without an emulator)
- Networking: Ktor client + kotlinx.serialization
- Storage: Room (KMP) for feedback records, DataStore for settings
- Dates/times: kotlinx-datetime
- No ML library. The regression is plain Kotlin linear algebra.

## Module layout

```
shared/
  weather/      Open-Meteo client, DTOs, mapping to HourlyWeather
  drying/       physics formula, simulation, recommendation, verdict
  learning/     feature extraction, ridge regression, correction
  data/         Room entities/DAOs, repositories
androidApp/
  ui/           input screen, result screen, feedback dialog
```

Rule: everything in `drying/` and `learning/` is pure Kotlin with no Android or I/O dependencies, so it is fully unit-testable.

## Weather input (Open-Meteo)

```
GET https://api.open-meteo.com/v1/forecast
  ?latitude={lat}&longitude={lon}
  &hourly=temperature_2m,relative_humidity_2m,wind_speed_10m,shortwave_radiation,precipitation_probability,precipitation
  &daily=sunrise,sunset
  &wind_speed_unit=ms
  &timezone=auto
  &forecast_days=2
  &models=jma_msm,best_match
```

Model choice (target region: Korea), checked 2026-10-28:
- `jma_msm` (JMA MSM, 5 km) covers Korea and Japan and supplies temperature, humidity, wind and radiation, but never `precipitation_probability`.
- `best_match` supplies rain probability, fills any MSM gaps, and replaces MSM entirely outside its domain.
- Per field and hour, take the first non-null of `<field>_jma_msm`, `<field>_best_match`, `<field>`. Outside the MSM domain Open-Meteo returns unsuffixed keys.
- Not used: Open-Meteo's KMA models (no data since 2026-04-02; every value is null), KMA's own API (needs a key, no solar radiation), Singapore NEA (no hourly numeric forecast).

No API key. Cache the last successful response; fall back to it when offline.

### Location

- Ask for coarse location permission on first launch and use the device location.
- If denied, ask once for a city name, resolve it with the Open-Meteo geocoding API, and store the lat/lon in DataStore. Use it from then on.

```
GET https://geocoding-api.open-meteo.com/v1/search?name={city}&count=5&language=en&format=json
```

Show the top results so the user picks the right one (`results[].name`, `admin1`, `country`, `latitude`, `longitude`). This is the one fixed location; there is no UI to manage several.

The input screen shows the current location (city name, or "Current location"). Tapping it reopens the city dialog, with an option to switch back to device location. That is the only way to change it.

### Actual weather for feedback

When feedback is saved, re-fetch the drying period with `&past_days=1` added to the forecast request (allowed 0–92), so hours that have already passed are included. Open-Meteo returns past hours as archived model data, not station observations; treat it as "closer to what happened than the forecast was". If the re-fetch fails (offline), keep the forecast snapshot taken at hang time and mark the record `weatherSource = FORECAST`.

```kotlin
data class HourlyWeather(
    val time: LocalDateTime,
    val tempC: Double,
    val rhPercent: Double,
    val windMs: Double,        // at 10 m
    val radiationWm2: Double,  // shortwave
    val rainProb: Int,         // %
    val rainMm: Double,
)
```

## User input

Kept to two or three taps.

```kotlin
enum class Fabric(val units: Double) {
    THIN_TOP(5.0), TSHIRT(7.0), TOWEL(11.0), HOODIE(15.0), JEANS(17.0), BEDDING(22.0)
}
enum class Crowding(val factor: Double) { SPACED(1.0), NORMAL(1.15), CROWDED(1.3) }
enum class Spot(val windFactor: Double, val sunFactor: Double, val rainExposed: Boolean) {
    OPEN_YARD(0.8, 1.0, true),
    BALCONY(0.5, 0.7, false),
    INDOOR(0.0, 0.0, false),
}
```

The user picks only the **slowest item** in the load, because the load is done when that item is dry.

## Core logic

### 1. Physics drying rate (per hour)

```
es    = 0.6108 · exp(17.27·T / (T + 237.3))     // saturation vapor pressure, kPa
VPD   = es · (1 − RH/100)                        // drying power of the air
wind  = windMs · spot.windFactor
fWind = 1 + 0.5 · sqrt(wind)
fSun  = 1 + (radiation · spot.sunFactor) / 600
rate  = VPD · fWind · fSun
```

INDOOR ignores the forecast and uses fixed conditions: T = 22 °C, RH = 55 %, wind = 0, no sun.

### 2. Drying threshold

```
D = fabric.units · crowding.factor
```

### 3. Simulation

Given a start time, step through hourly weather (interpolate partial hours linearly) and accumulate:

```
progress += rate · correction
```

Dry when `progress ≥ D`. Return the finish time, or a cutoff reason.

Cutoffs (outdoor spots only):
- **Rain** (only if `spot.rainExposed`): hour with `rainProb ≥ 50` or `rainMm > 0.1`. Laundry must be in before that hour starts.
- **Night**: stop accumulating at `sunset + 1h`. Not dry by then = "won't finish today".

### 4. Recommendation

1. Candidate start times: each full hour from the next hour until `min(14:00, sunset − 3h)`.
2. Simulate each candidate for the chosen spot.
3. Verdict for a candidate:
   - `GO` — finishes ≥ 60 min before the first cutoff
   - `RISKY` — finishes, but within 60 min of a cutoff
   - not finishing → fails
4. Output:
   - **Hang by** = latest start with `GO` (else latest `RISKY`)
   - **In by** = predicted finish for the earliest viable start
   - **Overall verdict**:
     - `HANG_OUT` if any start is `GO`
     - `RISKY` if only `RISKY` starts exist
     - `INDOOR` if no outdoor start works and INDOOR finishes within 12 h
     - `DRYER` otherwise
   - **Why**: average RH, wind, sun, and the cutoff reason, for the "why?" breakdown

### 5. Confidence range

Rerun the simulation with perturbed weather:
- RH ± 10 points (clamped 0–100)
- wind × 0.7 and × 1.3

The earliest and latest finishes form the range. Once the learning model has ≥ 5 records, widen or narrow the range using the model's residual standard deviation.

## Learning (on-device)

### Feedback record

```kotlin
data class FeedbackRecord(
    val id: Long,
    val spot: Spot,
    val fabric: Fabric,
    val crowding: Crowding,
    val hangTime: LocalDateTime,
    val predictedFinish: LocalDateTime,   // physics only, correction = 1.0
    val checkTime: LocalDateTime,
    val result: Result,                   // DRY, STILL_DAMP, SKIPPED
    val actualFinish: LocalDateTime?,     // optional, if the user knows it
    val features: Features,               // snapshot of weather during drying
    val weatherSource: WeatherSource,     // PAST_DAYS (re-fetched at feedback) or FORECAST (fallback)
)
```

The feedback dialog offers three answers: **Dry**, **Still damp**, and **Brought it in early / skip**. `SKIPPED` closes the record (no more reminders or launch prompts) but is never used for training and does not count toward the 5-record minimum, because an early take-in says nothing about how fast the spot dries.

Store the weather snapshot with the record, so the model can be retrained without re-fetching history. Take a forecast snapshot at "I hung it"; replace it with the re-fetched `past_days` data when feedback is saved (see Actual weather for feedback).

### Target

```
actualHours =
    actualFinish known → actualFinish − hangTime
    DRY at check       → checkTime − hangTime          (upper bound, accepted approximation)
    STILL_DAMP         → checkTime − hangTime + 1h      (lower bound, accepted approximation)

y = ln(predictedHours / actualHours)   // > 0 means physics was too slow
```

### Features (per record, averaged over the drying period)

```
x = [1, meanRH/100, meanWind, meanRadiation/600, afternoonShare, fabric.units/22]
```

`afternoonShare` = fraction of drying hours after 13:00 (captures shading that changes by time of day).

### Model

Ridge regression, trained separately per `Spot`, closed form:

```
w = (XᵀX + λI)⁻¹ Xᵀy      λ = 1.0, intercept not penalized
correction = clamp(exp(w · x), 0.5, 2.0)
```

- Fewer than 5 records for a spot → `correction = 1.0` (physics only).
- Retrain after every new feedback record (data is tiny; this is instant).
- Implement the 6×6 solve with Gaussian elimination in plain Kotlin.

### Evaluation

`learning/` includes a synthetic data generator: physics + a hidden spot effect (for example, sun drops to 30 % after 14:00) + noise. Use it to check that:
- the hybrid model's error is lower than physics-only on held-out data
- the learned weights move in the direction of the hidden effect

## Tests

Located in `shared/src/commonTest`. Required before any UI work:

| Scenario | Expected |
|---|---|
| 25 °C, 50 % RH, 2 m/s, cloudy, towel, open yard | dry in ~3–5 h |
| 30 °C, 85 % RH, 0 m/s, towel, open yard | slow; `RISKY` or worse |
| 12 °C, 35 % RH, 5 m/s, sunny, towel, open yard | faster than the first case |
| Rain at 15:00, open yard | in by ≤ 14:00, not `HANG_OUT` if it can't finish |
| Rain at 15:00, balcony | rain ignored |
| Any weather, indoor | uses fixed indoor conditions |
| Ridge regression on synthetic data | recovers the hidden effect's direction |
| < 5 records | correction exactly 1.0 |
| 4 real + any number of `SKIPPED` records | still correction 1.0; skipped never enter training |

Constants (600, 0.5, fabric units, spot factors) are **initial estimates**. Tune them against real measured drying times; update the test ranges when the constants change, and note why in the commit message.

## Notifications

One notification only: a "Did it dry?" reminder that brings the user back to give feedback.

- Scheduled when the user confirms they hung the laundry, for the predicted finish time (physics × correction).
- Use WorkManager one-time work with an initial delay; minute-level accuracy is enough, so no exact alarms.
- Request `POST_NOTIFICATIONS` (Android 13+) at that moment, not on first launch. If denied, the app still works.
- Tapping it opens the app straight to the feedback dialog for that record.
- Hanging a new load replaces any pending reminder.
- After "I hung it", show a snackbar with **Undo** (default duration). Undo deletes the record and cancels its reminder, as if the tap never happened.
- Lives in `androidApp`; nothing in `drying/` or `learning/` knows about it.

If the reminder is missed or denied, the app shows the feedback dialog on next open when a hang from the last 24 h has no feedback yet.

## Project decisions

Compose/CMP work: load the compose skill first, and use it for UI guidance (state placement, stability, accessibility, loading/empty/error states, M3 Expressive). The decisions below override its architecture rules. Shared reason for all of them: this is a one-week, two-screen app; the kit's multi-feature architecture costs more than it saves here.

- **Modules:** keep the `shared` + `androidApp` layout from Module layout. No `:core:*` / `:feature:*` modules, no `build-logic` convention plugins. Versions still live once in `gradle/libs.versions.toml`.
- **DI:** no Koin. Construct dependencies by hand in one place in `androidApp` and pass them in.
- **Navigation:** no Navigation 3. Switch between input and result screens with a single state value in the host composable; the feedback dialog is shown from the result screen's state, or on launch from the reminder notification.
- **ViewModels:** plain `ViewModel` with a `StateFlow<UiState>` and public functions per user action. No `BaseViewModel`, `Contract.kt`, or `UiEffect` channel.
- **Errors:** plain `try/catch` in `viewModelScope.launch` is fine; always rethrow `CancellationException`. Weather failure falls back to the cached forecast; with no cache, the result screen shows an inline error with Retry.
- **Guards:** do not install the kit's guard scripts or `.composekit.conf`.

## Do not build

- Photo / Gemini input
- Notifications other than the single "Did it dry?" reminder (see Notifications)
- Accounts, login, cloud sync, backend server
- Chat UI or any LLM text generation
- Multiple saved locations (use the device location or one fixed location)
- Settings screens beyond spot selection and the location dialog

If a task seems to need one of these, stop and ask.

## Build order

1. Project skeleton (shared + androidApp, jvm target for tests)
2. Open-Meteo client + DTO mapping + cached fallback + location (device, else geocoded city)
3. Physics rate + simulation + tests
4. Recommendation, verdict, confidence range + tests
5. Input screen (fabric, crowding, spot)
6. Result screen (hang by, in by, verdict, ± range, why)
7. "I hung it" + feedback dialog + Room storage with weather snapshot (`past_days` re-fetch, forecast fallback)
8. "Did it dry?" reminder notification + open-to-feedback fallback
9. Feature extraction + ridge regression + tests
10. Plug correction into the simulation
11. Synthetic data generator + evaluation tests

## Commands

```
./gradlew :shared:jvmTest                  # logic tests
./gradlew :androidApp:testDebugUnitTest    # ViewModel tests (JVM)
./gradlew :androidApp:assembleDebug        # build the APK
./gradlew :androidApp:installDebug         # run on device/emulator
```

Task names verified on 2026-10-28.

Environment:
- Run Gradle with JDK 25 (Android Studio's bundled JBR, e.g. `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`). The JDK 26 on PATH is untested with this toolchain.
- Needs Android SDK Platform 37.2 (`sdkmanager "platforms;android-37.2"`): Compose 1.13.0-beta01 requires compileSdk 37.1+, set via `compileSdkMinor`.
- `local.properties` holds `sdk.dir` and is not committed.

Test device: Windows Subsystem for Android (Android 13).
- `adb connect localhost:58526`. It listens on IPv6 only, so `127.0.0.1` is refused; right after WSA starts, retry a few times while it boots.
- `adb shell input tap` does not reach app windows (focus stays on WSA's placeholder activity), so UI interaction is checked by hand.
- Screenshots: `adb exec-out screencap -p > file.png`. In Git Bash, set `MSYS_NO_PATHCONV=1` for commands with device paths like `/sdcard`.
- Location comes from Windows via the passive provider; there is no network location provider.

## Working rules

- One feature per session: implement, run tests, commit.
- Keep `drying/` and `learning/` pure and deterministic. No randomness outside the synthetic data generator (seeded).
- Explain any non-obvious formula choice in a short comment next to the code.
- Don't change constants silently. Changes go with an updated test and a reason.
- Prefer simple, readable code over abstractions. This is a one-week project.

## Version control

Git, hosted on GitHub. Solo developer, so keep the workflow light.

### Branches

- `main` is always buildable and its tests pass. Never commit broken code to it.
- Work on short-lived branches named `type/short-description` in kebab-case, for example `feat/physics-simulation`, `fix/rain-cutoff`, `test/ridge-regression`.
- One branch per build-order step. Merge into `main` when the step's tests pass, then delete the branch.
- Merge with a fast-forward or squash merge to keep history linear. No long-lived branches.

### Commits

Use Conventional Commits: `type(scope): summary`

- Types: `feat`, `fix`, `test`, `refactor`, `docs`, `chore`, `build`
- Scopes: `weather`, `drying`, `learning`, `data`, `ui`, `build`
- Summary: imperative mood, lowercase, no trailing period, at most 72 characters
- Examples:
  - `feat(drying): add hourly physics rate and simulation`
  - `test(drying): cover rain cutoff for open yard`
  - `fix(learning): clamp correction to 0.5-2.0`
  - `chore(build): add ktor and serialization dependencies`
- Constant changes use a body explaining why:
  ```
  refactor(drying): lower balcony sun factor to 0.6

  Measured towel took 5.5h vs 4.2h predicted on a shaded balcony.
  ```

Commit rules:
- Small, focused commits: one logical change each, never mixed formatting and behavior changes.
- Run `./gradlew :shared:jvmTest` before every commit that touches `drying/` or `learning/`.
- Commit often (at least after each passing test or working screen). Pushing to GitHub daily is the backup.
- Do not commit commented-out code, debug prints, or unfinished TODO stubs.

### Never commit

- API keys, tokens, keystores, `local.properties`, or any secret
- `build/`, `.gradle/`, `.idea/`, `*.iml`, and other generated or IDE files
- Large binaries or screenshots (keep them out of the repo, or in a `docs/` folder at small sizes)

Add a Kotlin/Android/KMP `.gitignore` in the first commit.

### Tags

- Tag `v0.1`, `v0.2`, ... at stable points, for example after the physics core works, after the full core loop works, and at feature freeze.
- Tag the final presented build so it can always be restored.

### Rules for Claude Code

- Do not push, force-push, rebase, reset `--hard`, or delete branches unless explicitly asked.
- Before committing, show the staged file list and the proposed commit message.
- Never use `git add .` blindly; check `git status` for unintended files first.
- If tests fail, do not commit; fix or ask.