# Performance & Battery

- Max FPS: 20–30.
- Render nur wenn sichtbar.
- Sensoren nur wenn sichtbar; bevorzugt SENSOR_DELAY_UI.
- Keine Objekt-Allokationen pro Frame: Paint/Path/Arrays/Particles wiederverwenden.
- Profiling: logge Frames >33ms (bei 30 FPS).
