# Architecture

## Modules (single-module project)
- wallpaper/: WallpaperService + Engine + Render Loop
- rendering/: Szene/Layers (Sky/Mountains/Trees/Weather/Fog)
- parallax/: Sensor-Input → Offsets
- prefs/: Settings UI (PreferenceFragmentCompat)
- easter/: Multi-Tap detector

## Render
- Canvas Layering + translate() pro Layer für Parallax.
- Qualität/FPS abhängig von Settings und Battery Saver.
