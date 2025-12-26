#!/bin/bash
set -eu

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

APK_FILE="app/build/outputs/apk/debug/app-debug.apk"

if [ ! -f "$APK_FILE" ]; then
    echo "❌ APK nicht gefunden!"
    echo ""
    echo "Bitte zuerst bauen:"
    echo "  ./gradlew assembleDebug"
    echo ""
    read -p "Jetzt bauen? [y/N] " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        ./gradlew assembleDebug
    else
        exit 1
    fi
fi

echo ""
python3 "$SCRIPT_DIR/apk_server.py"
