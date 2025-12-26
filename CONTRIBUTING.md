# Contributing

## Lizenz
Mit deinem Beitrag erklärst du dich einverstanden, dass deine Änderungen unter der **CC BY-NC 4.0** Lizenz veröffentlicht werden.

## Regeln
- Kleine PRs, klarer Scope.
- Keine Allokationen im Render-/Draw-Loop (Paint/Path/Arrays wiederverwenden).
- Sensoren nur aktivieren wenn sichtbar.

## Dev-Setup
```bash
gradle wrapper
./gradlew test assembleDebug
```
