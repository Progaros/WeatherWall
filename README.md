# WeatherWall

https://ci.olli.info/api/badges/4/status.svg](https://ci.olli.info/repos/4)

📥 **https://git.olli.info/Oliver/WeatherWall/releases/latest**

Android live wallpaper with a low-poly procedural landscape, weather effects, seasonal details, and gyroscope-driven parallax.

No analytics. No internet permission.

<p align="center">
  app/src/main/res/logo.png
</p>

## Screenshots

<p align="center">
  docs/screenshots/Screenshot_1.png
  docs/screenshots/Screenshot_2.png
  docs/screenshots/Screenshot_3.png
  docs/screenshots/Screenshot_4.png
  docs/screenshots/Screenshot_5.png
</p>

## Features

- Procedural low-poly terrain with layered hills and mountains
- Dynamic sky with sun, moon, stars, and time-of-day lighting
- Weather effects: rain, snow, hail, lightning, clouds
- Seasonal visuals: flowers, mushrooms, autumn leaves, snow, frost
- Seasonal events: Christmas, New Year, St. Patrick's Day
- Trees, bushes, rivers/lakes, animals, and random wildlife events
- Gyroscope-based parallax
- Deterministic world generation from a stable seed
- Configurable density, clustering, parallax, weather, and terrain settings
- Optimized rendering with bitmap caches and zero per-frame allocation design

## Build

Requirements:

- Android 8.0+ / API 26+
- JDK 17
- Gradle 8.13+

OR

- Docker

```bash
git clone https://git.olli.info/Oliver/WeatherWall.git
cd WeatherWall
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
````

## Development

Run unit tests:

```bash
./gradlew test
```

Run lint:

```bash
./gradlew lintDebug
```

Run mutation tests:

```bash
./gradlew :app:pitest
```

## Docker

Build the CI image:

```bash
docker build -t weatherwall-builder -f Dockerfile.build .
```

Run tests in Docker:

```bash
docker run --rm -v "$PWD":/workspace -w /workspace weatherwall-builder ./gradlew test --no-daemon
```

Run mutation tests in Docker:

```bash
docker run --rm -v "$PWD":/workspace -w /workspace weatherwall-builder ./gradlew :app:pitest --no-daemon
```
