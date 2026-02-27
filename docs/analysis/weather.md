# Weather System Analysis

## WeatherType.kt (12 lines)
**Purpose:** Enum defining canonical weather conditions: CLEAR, PARTLY_CLOUDY, CLOUDY, OVERCAST, RAIN, SNOW, HAIL, THUNDERSTORM, FOG.

**Approach:**
- Internal weather vocabulary — decouples external weather API codes from rendering logic
- HAIL defined but not mapped from any incoming BreezyWeather code (reserved/manual-override only)

---

## WeatherRepository.kt (239 lines)
**Purpose:** Singleton persistence layer for weather data via SharedPreferences — central cache between broadcast receiver and wallpaper renderer.

**Approach:**
- **Repository pattern:** Single source of truth abstracting SharedPreferences storage
- `ExtendedWeatherData` DTO: 12 fields (precipitation probability, cloud cover, humidity, visibility, pressure, moon phase/rise/set, wind direction, temperature, feels-like, dew point)
- **Temperature precision:** Celsius × 10 as Int — 0.1° precision without float serialization issues. `Int.MIN_VALUE` sentinel for "unknown"
- Moon illumination: `(1 − cos(phase°)) / 2` converts 0–360° → 0–1
- **Deterministic daily wind direction:** When no real data, `daysSinceEpoch × 1234567 + 987654 mod 360` — all instances show same direction
- Normalized getters: wind 0–20 m/s → 0–1; visibility 0–10 km → 0–1; precip 0–100% → 0–1
- `apply()` for async writes; `isDataFresh()` default staleness 1 hour
- Default moon phase 180° (full moon) — ensures visible moon when no data received
- `Float.NaN` as "unknown" sentinel for temperature fields

---

## BreezyWeatherReceiver.kt (123 lines)
**Purpose:** BroadcastReceiver ingesting weather JSON from Breezy Weather (Gadgetbridge protocol).

**Approach:**
- **Async broadcast processing:** `goAsync()` + `ExecutorService` off main thread prevents ANR. `pendingResult.finish()` guaranteed via `finally`
- **Adapter pattern:** Maps OWM condition codes (200–804) to internal WeatherType via range-based `when`
- **Temperature unit detection:** Heuristic `> 100` distinguishes Kelvin from Celsius
- Wind speed conversion: km/h → m/s via `÷ 3.6`
- Multi-key JSON fallbacks: `optLong("sunRise", optLong("todaySunrise", -1))` handles multiple Breezy Weather versions
- Single-thread ExecutorService (daemon) serializes all broadcasts — no concurrent writes
- Raw JSON stored for debugging
- Lightweight org.json.JSONObject parsing (no Gson/Moshi dependency)
