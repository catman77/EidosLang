# R14.1 validation

## Executed in the artifact environment

1. `core-model` and `core-canonical` compiled successfully with:
   - `kotlinc-jvm 1.9.0`
   - JRE 21.0.11.
2. Existing six R14 golden content/snapshot hashes remained unchanged.
3. Strict canonical parser round-trip was executed.
4. Non-canonical JSON serialization was rejected.
5. Duplicate JSON keys were rejected.
6. Production-lineage mismatch was rejected.
7. Geometry-aware hit testing was executed for:
   - rotated black stick;
   - scaled colored circle;
   - negative point outside the stick geometry.
8. Group-aware single- and multi-selection semantics were executed.
9. Imported-document instance/group counter restoration was executed.
10. Existing editor reducer add/translate/undo/redo conformance remained PASS.

Full JVM log:
`tools/kotlin-conformance/RUN_LOG_R14_1.txt`

## Not executable in this artifact environment

- Android Gradle build (`assembleDebug`);
- Compose instrumentation test execution;
- emulator/device rendering.

Reason:
- no Android SDK / `ANDROID_HOME`;
- no Gradle installation in PATH;
- inherited archive still contains `gradle-wrapper.properties` but no `gradle-wrapper.jar`.

The Android instrumentation smoke-test source is included under:
`app/src/androidTest/java/org/eidolang/app/EditorSmokeTest.kt`.

The pinned production project remains Kotlin/Android tooling configuration from R14; the
1.9.0 compiler run is a pure-JVM core conformance check, not a substitute for the Android
Gradle/Compose build gate.
