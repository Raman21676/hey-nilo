cmake_minimum_required(VERSION 3.22.1)
project("studybuddy")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ============================================
# GGML (shared between llama.cpp and whisper.cpp)
# ============================================
set(GGML_VERSION_MAJOR 0)
set(GGML_VERSION_MINOR 9)
set(GGML_VERSION_PATCH 5)
set(GGML_VERSION "${GGML_VERSION_MAJOR}.${GGML_VERSION_MINOR}.${GGML_VERSION_PATCH}")
set(GGML_COMMIT "optimized")

set(LLAMA_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../llama.cpp)
set(GGML_ROOT ${LLAMA_ROOT}/ggml)

# GGML sources (shared)
set(GGML_SOURCES
    ${GGML_ROOT}/src/ggml.c
    ${GGML_ROOT}/src/ggml.cpp
    ${GGML_ROOT}/src/ggml-alloc.c
    ${GGML_ROOT}/src/ggml-backend.cpp
    ${GGML_ROOT}/src/ggml-backend-meta.cpp
    ${GGML_ROOT}/src/ggml-quants.c
    ${GGML_ROOT}/src/ggml-threading.cpp
    ${GGML_ROOT}/src/gguf.cpp
    ${GGML_ROOT}/src/ggml-opt.cpp
    ${GGML_ROOT}/src/ggml-backend-reg.cpp
    ${GGML_ROOT}/src/ggml-backend-dl.cpp
)

# CPU backend sources
set(GGML_CPU_SOURCES
    ${GGML_ROOT}/src/ggml-cpu/ggml-cpu.c
    ${GGML_ROOT}/src/ggml-cpu/ggml-cpu.cpp
    ${GGML_ROOT}/src/ggml-cpu/traits.cpp
    ${GGML_ROOT}/src/ggml-cpu/vec.cpp
    ${GGML_ROOT}/src/ggml-cpu/ops.cpp
    ${GGML_ROOT}/src/ggml-cpu/binary-ops.cpp
    ${GGML_ROOT}/src/ggml-cpu/unary-ops.cpp
    ${GGML_ROOT}/src/ggml-cpu/repack.cpp
    ${GGML_ROOT}/src/ggml-cpu/hbm.cpp
    ${GGML_ROOT}/src/ggml-cpu/quants.c
    ${GGML_ROOT}/src/ggml-cpu/llamafile/sgemm.cpp
    ${GGML_ROOT}/src/ggml-cpu/arch/arm/cpu-feats.cpp
    ${GGML_ROOT}/src/ggml-cpu/arch/arm/quants.c
    ${GGML_ROOT}/src/ggml-cpu/arch/arm/repack.cpp
)

# Llama sources
file(GLOB LLAMA_SOURCES
    ${LLAMA_ROOT}/src/llama*.cpp
    ${LLAMA_ROOT}/src/unicode*.cpp
)

# Model architecture sources
file(GLOB MODEL_SOURCES
    ${LLAMA_ROOT}/src/models/*.cpp
)

# ============================================
# WHISPER.CPP (for STT) - uses shared GGML
# ============================================
set(WHISPER_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../whisper.cpp)

set(WHISPER_SOURCES
    ${WHISPER_ROOT}/src/whisper.cpp
)

# ============================================
# JNI Bridge
# ============================================
set(JNI_SOURCES
    ai_chat_jni.cpp
    bridge_jni.cpp
    vad_jni.cpp
    audio_jni.cpp
    whisper_jni.cpp
)

# Create native library
add_library(
    ai-chat
    SHARED
    ${GGML_SOURCES}
    ${GGML_CPU_SOURCES}
    ${LLAMA_SOURCES}
    ${MODEL_SOURCES}
    ${WHISPER_SOURCES}
    ${JNI_SOURCES}
)

# Include directories
target_include_directories(ai-chat PRIVATE
    ${LLAMA_ROOT}
    ${LLAMA_ROOT}/src
    ${LLAMA_ROOT}/include
    ${GGML_ROOT}/include
    ${GGML_ROOT}/src
    ${GGML_ROOT}/src/ggml-cpu
    ${WHISPER_ROOT}/include
    ${WHISPER_ROOT}/src
    ${CMAKE_CURRENT_SOURCE_DIR}/onnxruntime
)

# ONNX Runtime library path
set(JNILIBS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
set(ONNXRUNTIME_LIB ${JNILIBS_DIR}/libonnxruntime.so)

if(NOT EXISTS ${ONNXRUNTIME_LIB})
    message(WARNING "ONNX Runtime not found at ${ONNXRUNTIME_LIB}")
    set(ONNX_AVAILABLE FALSE)
else()
    message(STATUS "Found ONNX Runtime: ${ONNXRUNTIME_LIB}")
    set(ONNX_AVAILABLE TRUE)
endif()

# ============================================
# OBOE AUDIO LIBRARY
# ============================================
include(FetchContent)
FetchContent_Declare(
    oboe
    GIT_REPOSITORY https://github.com/google/oboe.git
    GIT_TAG        1.9.0
)
FetchContent_MakeAvailable(oboe)

target_include_directories(ai-chat PRIVATE
    ${oboe_SOURCE_DIR}/include
)

# Link libraries
target_link_libraries(
    ai-chat
    android
    log
    dl
    ${ONNXRUNTIME_LIB}
    oboe
    OpenSLES
)

# ============================================
# COMPILER FLAGS
# ============================================
target_compile_options(ai-chat PRIVATE
    -O3
    -ffast-math
    -fno-finite-math-only
    -funroll-loops
    -ftree-vectorize
    -fomit-frame-pointer
)

set(CMAKE_INTERPROCEDURAL_OPTIMIZATION TRUE)

# ============================================
# DEFINITIONS
# ============================================
target_compile_definitions(ai-chat PRIVATE
    NDEBUG
    GGML_USE_CPU
    GGML_VERSION="${GGML_VERSION}"
    GGML_COMMIT="${GGML_COMMIT}"
    GGML_USE_NEON
    GGML_AVX=OFF
    GGML_AVX2=OFF
    GGML_FMA=OFF
    GGML_F16C=OFF
    WHISPER_VERSION="1.7.4"
)

# ============================================
# ARM64 SPECIFIC
# ============================================
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(ai-chat PRIVATE
        -march=armv8-a+fp+simd+crc+crypto
        -mtune=cortex-a53
        -mcpu=cortex-a53
    )
    
    target_compile_definitions(ai-chat PRIVATE
        GGML_NATIVE=OFF
        GGML_NO_ACCELERATE=ON
    )
    
    message(STATUS "Building for ARM64 with Cortex-A53 optimizations")
endif()

set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Suppress warnings
target_compile_options(ai-chat PRIVATE
    -Wno-unused-function
    -Wno-unused-variable
    -Wno-sign-compare
    -Wno-missing-field-initializers
)
