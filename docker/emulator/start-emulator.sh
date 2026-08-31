#!/usr/bin/env bash
set -euo pipefail

mkdir -p /artifacts /root/.android/avd

# Software rendering cost scales with pixel count. 1440x3120 needs ~75s per
# wallpaper frame; the default below keeps screenshots to a few seconds.
EMU_WIDTH="${EMU_WIDTH:-720}"
EMU_HEIGHT="${EMU_HEIGHT:-1560}"
EMU_DPI="${EMU_DPI:-280}"

echo "Starting virtual X display (${EMU_WIDTH}x${EMU_HEIGHT})..."
Xvfb :0 -screen 0 "${EMU_WIDTH}x${EMU_HEIGHT}x24" -ac +extension GLX +render -noreset >/tmp/xvfb.log 2>&1 &
sleep 2

echo "Starting window manager..."
openbox >/tmp/openbox.log 2>&1 &

echo "Starting VNC server..."
x11vnc -display :0 -forever -shared -nopw -rfbport 5900 >/tmp/x11vnc.log 2>&1 &

echo "Starting noVNC on :6080..."
websockify --web=/usr/share/novnc 6080 localhost:5900 >/tmp/novnc.log 2>&1 &

echo "Starting Android emulator (software rendering, no KVM)..."
emulator -avd wallpaper_api34 \
  -no-accel \
  -gpu swiftshader_indirect \
  -no-audio \
  -no-boot-anim \
  -no-snapshot \
  -wipe-data \
  -memory 3072 \
  -cores 4 \
  -skin "${EMU_WIDTH}x${EMU_HEIGHT}" \
  -camera-back none \
  -camera-front none \
  -port 5554 \
  >/tmp/emulator.log 2>&1 &

echo "Waiting for ADB..."
adb wait-for-device

echo "Waiting for Android boot (this takes a long time without KVM)..."
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 10
done

adb shell input keyevent 82 || true
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true
# Installs otherwise fail with "Integrity verification timed out" on this slow emulator.
adb shell settings put global verifier_verify_adb_installs 0 || true
adb shell settings put global package_verifier_enable 0 || true
adb shell settings put global hide_error_dialogs 1 || true
adb shell svc power stayon true || true
adb shell wm size "${EMU_WIDTH}x${EMU_HEIGHT}" || true
adb shell wm density "${EMU_DPI}" || true

echo
echo "Android is ready. Open http://localhost:6080"
echo

tail -F /tmp/emulator.log /tmp/xvfb.log /tmp/x11vnc.log /tmp/novnc.log
