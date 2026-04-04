#!/bin/bash
# AGENT SMITH Release Build Script
# Run this to build fresh APK and AAB with new branding

echo "🚀 Building AGENT SMITH Release..."
echo "This will take 30-40 minutes. Don't close this window."
echo ""

cd ~/Desktop/agent-smith/native_android

# Clean old builds
echo "📦 Cleaning old builds..."
./gradlew clean

# Build APK and AAB
echo "🔨 Building APK (this takes ~20 minutes)..."
./gradlew :app:assembleRelease

echo "🔨 Building AAB (this takes ~20 minutes)..."
./gradlew :app:bundleRelease

# Copy to final folder
echo "📋 Copying builds to agent-smith-final/apk/..."
mkdir -p ~/Desktop/agent-smith-final/apk
cp app/build/outputs/apk/release/app-release.apk ~/Desktop/agent-smith-final/apk/agent-smith-release.apk
cp app/build/outputs/bundle/release/app-release.aab ~/Desktop/agent-smith-final/apk/agent-smith-release.aab

echo ""
echo "✅ BUILD COMPLETE!"
echo "Files saved to: ~/Desktop/agent-smith-final/apk/"
ls -lh ~/Desktop/agent-smith-final/apk/
