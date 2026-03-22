# Hey-Nilo Security Audit Report

**Date:** March 22, 2026  
**App Version:** 1.0  
**Package:** com.projekt_x.studybuddy

---

## Executive Summary

| Severity | Count | Issues |
|----------|-------|--------|
| 🔴 CRITICAL | 2 | Hardcoded keystore password, allowBackup=true |
| 🟡 HIGH | 2 | No code obfuscation, debug logging in release |
| 🟢 LOW | 0 | None |
| ✅ GOOD | 5 | Secure API storage, encrypted prefs, etc. |

---

## 🔴 CRITICAL Issues (Must Fix Before Release)

### 1. Hardcoded Keystore Password in build.gradle
**File:** `native_android/app/build.gradle`  
**Lines:** 64-67

```gradle
storeFile = file("/Users/kalikali/Desktop/hey-nilo-final/keystore/heynilo-release.keystore")
storePassword = "heynilo123"
keyAlias = "heynilo"
keyPassword = "heynilo123"
```

**Risk:** Keystore password is visible in source code. Anyone with access to the codebase can extract the signing key.

**Fix:** Move credentials to `local.properties` (not committed to git) or use environment variables.

---

### 2. allowBackup="true" in AndroidManifest
**File:** `native_android/app/src/main/AndroidManifest.xml`  
**Line:** 16

**Risk:** App data (including encrypted API keys) can be backed up to Google Drive and restored on other devices.

**Fix:** Set `android:allowBackup="false"`

---

## 🟡 HIGH Issues (Should Fix)

### 3. No Code Obfuscation
**File:** `native_android/app/build.gradle`  
**Lines:** 73-74

```gradle
minifyEnabled = false
shrinkResources = false
```

**Risk:** App code is easily reverse-engineered. API keys, logic, and sensitive algorithms exposed.

**Fix:** Enable ProGuard/R8 obfuscation for release builds.

---

### 4. Debug Logging in Release
**Files:** Multiple files use `Log.d()`, `Log.i()` extensively

**Risk:** Sensitive information may be logged and accessible via logcat on rooted devices.

**Fix:** Disable logging in release builds or use BuildConfig.DEBUG checks.

---

## ✅ GOOD Security Practices

### 1. Secure API Key Storage ✓
- Uses `EncryptedSharedPreferences` with AES256_GCM
- MasterKey uses AES256_GCM scheme
- Keys are encrypted at rest

### 2. No Hardcoded API Keys ✓
- No API keys found in source code
- Keys entered by user and stored securely

### 3. Network Security ✓
- No cleartext traffic allowed
- HTTPS enforced for API calls
- Certificate pinning not implemented (acceptable for MVP)

### 4. Permission Model ✓
- Minimal permissions requested
- Dangerous permissions (CALL_PHONE, CAMERA) not included (removed in final build)
- Runtime permission handling present

### 5. Native Code Security ✓
- JNI calls properly secured
- No obvious buffer overflow vulnerabilities
- UTF-8 sanitization in place

---

## Recommendations

### Before Play Store Release:

1. **Move keystore credentials to local.properties**
2. **Disable allowBackup**
3. **Enable minification and shrinking**
4. **Remove or disable debug logging**

### Future Improvements:

1. Implement certificate pinning for API calls
2. Add root detection
3. Implement tamper detection
4. Use SafetyNet/Play Integrity API
5. Add screenshot prevention for sensitive screens

---

## Fixed In This Commit

- [x] Removed device control features (reduced attack surface)
- [x] Cleaned up unnecessary permissions
- [x] Verified secure API key storage implementation
