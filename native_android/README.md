# Study Buddy - Native Android

Native Android AI assistant with JNI to llama.cpp. Optimized for low-end devices.

## Quick Start

### Prerequisites

1. **Android Studio** Hedgehog (2023.1.1) or newer
2. **NDK** 27.0.12077973 (install via SDK Manager)
3. **CMake** 3.22.1+ (install via SDK Manager)

### Important: Java Version

**⚠️ Use Java 21 from Android Studio - NOT Java 25**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

### Build

```bash
cd /Users/kalikali/Projekt-X/native_android

# Clean build
./gradlew clean

# Build debug APK
./gradlew assembleDebug

# Install on device
./gradlew installDebug
```

### Model Setup

Place `tinyllama-1.1b-chat-v1.0.Q4_0.gguf` in:
```
/sdcard/Android/data/com.projekt_x.studybuddy/files/models/
```

Or the app will prompt for download on first launch.

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for detailed design documentation.

### Language Stack

| Layer | Language | Purpose |
|-------|----------|---------|
| UI | Kotlin + Compose | Native Android UI |
| Logic | Kotlin | Business logic, queue management |
| JNI | C++ | llama.cpp integration |
| Inference | C++ | GGML, ARM64 optimizations |

### Why Native Android (not Flutter)?

- **3-4MB less memory** (no Flutter engine)
- **Faster startup** (no engine initialization)
- **Direct JNI** (no FFI overhead)
- **Smaller APK**

## Device Configuration

Auto-detected based on RAM:

| RAM | Threads | Context | Batch |
|-----|---------|---------|-------|
| <1.5GB | 2 | 512 | 2 |
| 1.5-3GB | 3 | 1024 | 4 |
| >3GB | 4 | 2048 | 8 |

**Samsung Tab A7 Lite (4GB)**: 3 threads, 1024 ctx, batch 4

## Current Status

### ✅ Working
- Project structure
- Jetpack Compose UI
- Kotlin coroutines + Flow
- Device detection
- Queue management

### 🔄 In Progress
- Native library linking (llama.cpp)
- CMake configuration fixes

### ⏳ Pending
- On-device testing
- Performance optimization

## Troubleshooting

### "25.0.2" Build Error
Java 25 is not supported. Use Android Studio's bundled Java 21:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

### CMake Errors
Clean and rebuild:
```bash
rm -rf app/.cxx
./gradlew clean assembleDebug
```

### Missing Model
Download TinyLlama Q4_0:
```bash
wget https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_0.gguf
```

## Performance Targets

- **First token**: < 2 seconds
- **Generation**: 7+ tokens/second (Samsung Tab)
- **Memory**: < 1.5GB total

## License

Same as llama.cpp (MIT)
