# WeatherWall (FOSS)

Open-source, battery-friendly Android Live Wallpaper mit Vektor-Layern (Sky/Mountains/Trees), Wetter-Effekten (Rain/Snow/Lightning), saisonalen Events (Xmas/New Year), optionalem 3D-Parallax und Multi-Tap Easter-Egg.

## Highlights
- Privacy-first: keine Tracker, optional ohne INTERNET Permission.
- Battery-first: adaptive FPS (20–30), Sensoren nur wenn sichtbar, keine Allokationen im Render-Loop.
- Breezy Weather als Datenquelle ("soft contract" + Fallback).
- CI: Woodpecker Pipeline (SDK 35 Container).

## Quickstart
```bash
# Gradle wrapper erzeugen (falls gradlew fehlt)
gradle wrapper

./gradlew assembleDebug
```

## APK im LAN teilen
```bash
./gradlew assembleDebug
./scripts/start_apk_server.sh
# dann http://localhost:8080
```

## Lizenz

Dieses Projekt steht unter der **Creative Commons Attribution-NonCommercial 4.0 International License (CC BY-NC 4.0)**.

**Du darfst:**
- Den Code nutzen, verändern und teilen.
- Eigene Versionen bauen (Forks).

**Bedingungen:**
- **Keine kommerzielle Nutzung**: Du darfst diese App oder Abwandlungen davon nicht verkaufen (z.B. im Play Store gegen Geld oder mit Werbung).
- **Namensnennung**: Du musst einen sichtbaren Link auf dieses Original-Repo setzen und den Original-Autor (Oliver) nennen.

Vollständiger Lizenztext: [LICENSE](LICENSE) | [CC BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/)
