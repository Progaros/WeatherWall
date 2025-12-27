# Privacy

## Data Collection

**WeatherWall collects no data.** The app runs entirely on-device with no analytics, tracking, or network requests.

## Permissions

| Permission | Required | Purpose |
|-----------|----------|---------|
| None | - | No permissions needed for basic functionality |
| Accelerometer sensor | Optional | 3D parallax effect (no permission prompt needed) |

## Weather Data

- Weather data is received **locally** via Breezy Weather app broadcasts
- No network requests made by WeatherWall itself
- Data stored in local SharedPreferences only
- If no weather data available, defaults to clear sky

## Third-Party Integration

The only integration is with **Breezy Weather** via Gadgetbridge broadcasts:
- Breezy Weather sends weather updates as local Android broadcasts
- WeatherWall passively listens (no outbound communication)
- Integration is optional and can be disabled in settings
