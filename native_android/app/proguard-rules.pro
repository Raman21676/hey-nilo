# ProGuard rules for StudyBuddy

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep JNI bridge
-keep class com.projekt_x.studybuddy.LlamaBridge {
    *;
}

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Jetpack Compose
-keepclassmembers class androidx.compose.** { *; }

# llama.cpp native
-keep class * extends java.lang.Exception

# Sherpa-ONNX — keep all classes to prevent JNI method stripping
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }
