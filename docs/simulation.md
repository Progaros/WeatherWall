# Simulation System

The simulation system manages time, weather, and state for the wallpaper. This creates a dynamic environment independent of real time.

## Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                     SimulationController                        │
│                                                                 │
│   ┌───────────────┐  ┌───────────────┐  ┌───────────────────┐  │
│   │ SimulationClock│  │ WeatherRepo  │  │ SimulationSettings│  │
│   └───────────────┘  └───────────────┘  └───────────────────┘  │
│           │                  │                    │             │
│           └──────────────────┼────────────────────┘             │
│                              ▼                                  │
│                    ┌─────────────────┐                          │
│                    │ SimulationState │                          │
│                    └─────────────────┘                          │
│                              │                                  │
│                              ▼                                  │
│                        ParallaxScene                            │
└─────────────────────────────────────────────────────────────────┘
```

## Components

### SimulationClock

**File:** [simulation/SimulationClock.kt](../app/src/main/java/info/olli/weatherlwp/simulation/SimulationClock.kt)

Manages simulated time progression.

```kotlin
class SimulationClock(
    private val timeScale: Float = 1.0f,
    private val useRealTime: Boolean = false
)
```

**Properties:**
- `hour: Float` - Current hour (0.0 - 24.0)
- `dayOfYear: Int` - Day (1-365)
- `season: Season` - Current season enum

**Time Modes:**

| Mode | Description |
|------|-------------|
| Real Time | Uses actual device time |
| Simulated | Accelerated progression |
| Fixed | Locked to specific time |

**Season Detection:**
```kotlin
enum class Season { SPRING, SUMMER, AUTUMN, WINTER }

// Based on dayOfYear in Northern Hemisphere
// Spring: March 20 - June 20
// Summer: June 21 - September 22
// etc.
```

### SimulationState

**File:** [simulation/SimulationState.kt](../app/src/main/java/info/olli/weatherlwp/simulation/SimulationState.kt)

Immutable snapshot of current simulation state.

```kotlin
data class SimulationState(
    val time: Float,              // Hour 0.0-24.0
    val dayOfYear: Int,           // 1-365
    val season: Season,
    val weather: WeatherType,
    val windStrength: Float,      // 0.0-1.0
    val windGust: Float,          // 0.0-1.0
    val temperature: Float?,      // Celsius, nullable
    val isDebugMode: Boolean
)
```

**Usage:**
Layers receive SimulationState in their draw() method:

```kotlin
override fun draw(
    canvas: Canvas,
    state: SimulationState,
    parallaxCalculator: ParallaxCalculator
) {
    val timeOfDay = state.time  // 0.0 to 24.0
    val wind = state.windStrength + state.windGust
    // ... rendering based on state
}
```

### SimulationSettings

**File:** [simulation/SimulationSettings.kt](../app/src/main/java/info/olli/weatherlwp/simulation/SimulationSettings.kt)

Reads and provides settings from SharedPreferences.

```kotlin
class SimulationSettings(context: Context) {
    val timeMode: TimeMode
    val timeScale: Float
    val fixedTime: Float
    val useRealWeather: Boolean
    val debugWeather: WeatherType
    val debugWind: Float
    // ... more settings
}
```

### SimulationController

**File:** [simulation/SimulationController.kt](../app/src/main/java/info/olli/weatherlwp/simulation/SimulationController.kt)

Orchestrates all simulation components.

```kotlin
class SimulationController(context: Context) {
    private val clock: SimulationClock
    private val weatherRepo: WeatherRepository
    private val settings: SimulationSettings
    
    fun update(deltaTime: Float): SimulationState
}
```

**Responsibilities:**
1. Advance simulation clock
2. Check for weather updates
3. Calculate wind gusts
4. Build SimulationState

## Weather Integration

### WeatherRepository

**File:** [weather/WeatherRepository.kt](../app/src/main/java/info/olli/weatherlwp/weather/WeatherRepository.kt)

Provides current weather data.

```kotlin
class WeatherRepository(context: Context) {
    val currentWeather: WeatherType
    val windStrength: Float
    val temperature: Float?
    val lastUpdate: Long
    
    fun isStale(): Boolean
}
```

**Data Sources:**

1. **Breezy Weather** - Real weather via broadcast
2. **Debug Mode** - Manual selection in settings
3. **Fallback** - Clear weather if no data

### WeatherType

```kotlin
enum class WeatherType {
    CLEAR,
    PARTLY_CLOUDY,
    CLOUDY,
    RAIN,
    HEAVY_RAIN,
    THUNDERSTORM,
    SNOW,
    HEAVY_SNOW,
    FOG,
    UNKNOWN
}
```

### BreezyWeatherReceiver

**File:** [weather/BreezyWeatherReceiver.kt](../app/src/main/java/info/olli/weatherlwp/weather/BreezyWeatherReceiver.kt)

BroadcastReceiver for Breezy Weather updates.

```kotlin
class BreezyWeatherReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Parse weather data from intent
        // Store in SharedPreferences
    }
}
```

**Intent Format:**
Breezy Weather sends `org.breezyweather.WEATHER_UPDATE` with:
- `weather_kind` - Weather code
- `temperature` - Current temp
- `wind_speed` - Wind in m/s

## Time Progression

### Real Time Mode

```kotlin
if (useRealTime) {
    val calendar = Calendar.getInstance()
    hour = calendar.get(HOUR_OF_DAY) + 
           calendar.get(MINUTE) / 60f
}
```

### Simulated Time Mode

```kotlin
// timeScale = 60 means 1 real second = 1 sim minute
hour += deltaTime * timeScale / 3600f
if (hour >= 24f) {
    hour -= 24f
    dayOfYear++
}
```

### Fixed Time Mode

```kotlin
hour = settings.fixedTime  // Always same time
```

## Wind System

### Base Wind

From weather data:
```kotlin
windStrength = when (weather) {
    THUNDERSTORM -> 0.7f
    HEAVY_RAIN -> 0.5f
    RAIN -> 0.3f
    else -> 0.1f
}
```

### Wind Gusts

Random gusts for realism:

```kotlin
private var gustStrength = 0f
private var gustTimer = 0f

fun updateGusts(deltaTime: Float) {
    gustTimer -= deltaTime
    if (gustTimer <= 0) {
        gustStrength = Random.nextFloat() * 0.3f
        gustTimer = Random.nextFloat() * 5f + 2f
    }
    // Gust fades over time
    gustStrength *= 0.95f
}
```

**Effective Wind:**
```kotlin
val effectiveWind = state.windStrength + state.windGust
```

## State Immutability

SimulationState is a data class - immutable by design.

**Benefits:**
1. Thread safety
2. Easy comparison (`==`)
3. Predictable rendering
4. Testable

**Creating Updated State:**
```kotlin
val newState = currentState.copy(
    weather = newWeather,
    windGust = newGust
)
```

## Integration Example

```kotlin
class WeatherWallpaper : WallpaperService() {
    
    inner class WallpaperEngine : Engine() {
        private val controller = SimulationController(context)
        private val scene = ParallaxScene()
        private var lastFrameTime = System.currentTimeMillis()
        
        private fun drawFrame() {
            val now = System.currentTimeMillis()
            val deltaTime = (now - lastFrameTime) / 1000f
            lastFrameTime = now
            
            // Update simulation
            val state = controller.update(deltaTime)
            
            // Render with current state
            val canvas = surfaceHolder.lockCanvas()
            scene.draw(canvas, state)
            surfaceHolder.unlockCanvasAndPost(canvas)
        }
    }
}
```

## Testing

SimulationState is easily mockable:

```kotlin
@Test
fun testNightRendering() {
    val state = SimulationState(
        time = 23.0f,  // 11 PM
        dayOfYear = 180,
        season = Season.SUMMER,
        weather = WeatherType.CLEAR,
        windStrength = 0.1f,
        windGust = 0f,
        temperature = 20f,
        isDebugMode = false
    )
    
    val layer = SkyLayer()
    // Test night sky rendering...
}
```
