#!/bin/bash
set -e

# Setup script for HTTP Toolkit Android integration tests
# Ensures device is ready and app is installed before running tests

PACKAGE="tech.httptoolkit.android.v1"
APK_PATH="../app/build/outputs/apk/debug/app-debug.apk"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

cd "$SCRIPT_DIR"

echo "Waiting for device..."
adb wait-for-device

echo "Waiting for boot to complete..."
adb shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'
echo "Device ready."

if adb shell pm list packages | grep -q "$PACKAGE"; then
    echo "App already installed."
else
    if [[ -f "$APK_PATH" ]]; then
        echo "Installing APK..."
        adb install -t "$APK_PATH"
        echo "App installed."
    else
        echo "APK not found at $APK_PATH"
        echo "Building APK..."
        (cd .. && ./gradlew assembleDebug)
        adb install -t "$APK_PATH"
        echo "App built and installed."
    fi
fi

echo "Waking screen..."
adb shell input keyevent KEYCODE_WAKEUP

echo "Setup complete. Run 'npm test' to start tests."
