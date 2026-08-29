# R22 Android runtime admission

## Current artifact environment

The current build container has:

- no configured `ANDROID_HOME`;
- no configured `ANDROID_SDK_ROOT`;
- no `adb`;
- no system `gradle`;
- no vendored `gradle-wrapper.jar`.

Therefore R22 cannot truthfully claim an Android APK build or AndroidKeyStore runtime pass in
this environment.

## Prepared local runner

Use:

```bash
tools/r22-android-admission.sh
```

on a machine with the Android SDK.

The script:

1. checks SDK availability;
2. uses the vendored wrapper if present, otherwise system Gradle;
3. runs `:app:assembleDebug`;
4. computes the APK SHA-256;
5. if an adb device/emulator is connected, runs `:app:connectedDebugAndroidTest`;
6. installs the debug APK.

## In-app admission

After installation:

```text
Эйдограммы → Диагностика → Запустить admission probe
```

Required checks:

- `identity.bundle`
- `identity.sign`
- `identity.rsa_oaep`
- `local.secret_box`
- `vault.secret_store`
- `repository.sensitive_text`

The required product condition is:

\[
AndroidAdmissionReport.passed=true.
\]

Until this is observed on an actual Android runtime, the exact stage status is
`SOURCE COMPLETE / RUNTIME ADMISSION PENDING`.

## Instrumentation source

The Android smoke tests are updated for R22:

- first-run primary creation when required;
- home navigation;
- Devices;
- Diagnostics;
- secure archive/recovery controls.

They are present but were not executed in this container.
