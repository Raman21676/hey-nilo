#!/bin/bash
# Install AGENT SMITH app and push cached models

set -e

echo "=== Installing AGENT SMITH with Model Cache ==="

# Uninstall old app
echo "Uninstalling old app..."
adb uninstall com.projekt_x.studybuddy || true

# Install new APK
echo "Installing new APK..."
adb install native_android/app/build/outputs/apk/debug/app-debug.apk

# Create models directory on device
echo "Creating models directory..."
adb shell mkdir -p /sdcard/Android/data/com.projekt_x.studybuddy/files/models/

# Push cached LLM model if exists
if [ -f "models_cache/qwen2.5-0.5b-instruct-q4_0.gguf" ]; then
    echo "Pushing Qwen 2.5 0.5B model (409MB)..."
    adb push models_cache/qwen2.5-0.5b-instruct-q4_0.gguf /sdcard/Android/data/com.projekt_x.studybuddy/files/models/
    echo "✓ Model pushed successfully"
else
    echo "⚠ No cached model found - you'll need to download it again"
fi

# Grant permissions
echo "Granting permissions..."
adb shell pm grant com.projekt_x.studybuddy android.permission.RECORD_AUDIO || true
adb shell pm grant com.projekt_x.studybuddy android.permission.READ_EXTERNAL_STORAGE || true
adb shell pm grant com.projekt_x.studybuddy android.permission.WRITE_EXTERNAL_STORAGE || true

echo ""
echo "=== Installation Complete ==="
echo "App installed with cached model - ready to use!"
