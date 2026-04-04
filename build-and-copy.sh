#!/bin/bash
# AGENT SMITH - Complete Build Script
# This builds fresh APK/AAB and copies to agent-smith-final/apk/

set -e  # Exit on error

echo "╔════════════════════════════════════════════════════════╗"
echo "║     AGENT SMITH - Release Build Script                 ║"
echo "║     This will take 30-40 minutes                       ║"
echo "╚════════════════════════════════════════════════════════╝"
echo ""

# Navigate to project
cd ~/Desktop/agent-smith/native_android

echo "📦 Step 1: Cleaning old builds..."
rm -rf app/.cxx app/build .gradle
echo "✅ Clean complete"
echo ""

echo "🔨 Step 2: Building Release APK..."
echo "   (This takes ~20 minutes - compiling C++ code)"
./gradlew :app:assembleRelease --no-daemon
echo "✅ APK build complete"
echo ""

echo "🔨 Step 3: Building Release AAB..."
echo "   (This takes ~15 minutes)"
./gradlew :app:bundleRelease --no-daemon
echo "✅ AAB build complete"
echo ""

echo "📋 Step 4: Copying to agent-smith-final/apk/..."
mkdir -p ~/Desktop/agent-smith-final/apk
cp app/build/outputs/apk/release/app-release.apk ~/Desktop/agent-smith-final/apk/agent-smith-release.apk
cp app/build/outputs/bundle/release/app-release.aab ~/Desktop/agent-smith-final/apk/agent-smith-release.aab
echo "✅ Files copied"
echo ""

echo "╔════════════════════════════════════════════════════════╗"
echo "║              BUILD SUCCESSFUL!                         ║"
echo "╚════════════════════════════════════════════════════════╝"
echo ""
echo "📍 Files saved to:"
echo "   ~/Desktop/agent-smith-final/apk/"
echo ""
ls -lh ~/Desktop/agent-smith-final/apk/
echo ""
echo "🚀 Next steps:"
echo "   adb install -r ~/Desktop/agent-smith-final/apk/agent-smith-release.apk"
