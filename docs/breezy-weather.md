# Breezy Weather Setup

WeatherWall can display real weather conditions by integrating with the [Breezy Weather](https://github.com/breezy-weather/breezy-weather) app.

## What is Breezy Weather?

Breezy Weather is a free, open-source weather app for Android. WeatherWall receives weather data from Breezy Weather via Android broadcast intents - no internet connection is required by WeatherWall itself.

## Installation

### 1. Install Breezy Weather

Download from:
- [F-Droid](https://f-droid.org/packages/org.breezyweather/)
- [GitHub Releases](https://github.com/breezy-weather/breezy-weather/releases)

### 2. Configure Breezy Weather

Open Breezy Weather and:

1. Set up your location (either manually or via GPS)
2. Go to **Settings** → **External Modules**
3. Enable **"Send Gadgetbridge data"**

This sends weather updates to WeatherWall automatically.

### 3. Enable in WeatherWall

In WeatherWall settings:

1. Enable **"Use Breezy Weather"** toggle
2. Tap **"Check Breezy Weather"** to verify connection

## Status Messages

When checking Breezy Weather status, you'll see:

| Status | Meaning |
|--------|---------|
| ✅ Connected | Receiving weather data successfully |
| ⚠️ Data is stale | BW found but data is old (>2 hours) |
| ⚠️ No data received | BW installed but not sending data |
| ❌ Not installed | Breezy Weather app not found |

## Weather Types

WeatherWall responds to these weather conditions:

| Weather | Visual Effect |
|---------|---------------|
| Clear | No precipitation, bright sky |
| Cloudy | Increased cloud density |
| Rain | Rain particles with splashes |
| Snow | Snowflakes falling |
| Thunderstorm | Rain + lightning flashes |
| Fog | Fog overlay effect |

## Troubleshooting

### No Data Received

1. Ensure Breezy Weather is configured with a location
2. Check that "Send Gadgetbridge data" is enabled
3. Wait for Breezy Weather to refresh (pull down in app)
4. Restart WeatherWall

### Stale Data

Breezy Weather may have stopped sending updates:

1. Open Breezy Weather
2. Pull down to refresh weather
3. Check battery optimization isn't killing BW

### App Not Found

WeatherWall looks for these package names:
- `org.breezyweather`
- `org.breezyweather.debug`
- `org.breezyweather.freenet`
- `wangdaye.com.geometricweather`

If using a fork with different package name, it won't be detected.

## Manual Weather (Debug Mode)

For testing without Breezy Weather:

1. Enable **Debug Mode** in settings
2. Use **Weather Type** selector to choose weather
3. Adjust **Wind Strength** manually

This is also useful for screenshots and testing.

## Privacy

- WeatherWall has **no INTERNET permission**
- All weather data comes locally from Breezy Weather
- No data is sent anywhere by WeatherWall
- Breezy Weather handles all network requests
