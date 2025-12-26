#!/bin/sh
set -eu

if [ ! -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
  echo "APK fehlt. Build zuerst:"
  echo "  ./gradlew assembleDebug"
  exit 1
fi

python3 ./scripts/apk_server.py
