# R14 validation

## Executed in the artifact environment
1. Corrected R13.1 catalog hash recomputed from canonical JSON excluding `catalog_hash`.
2. Catalog checked for 113 unique glyph IDs and exact family counts.
3. Six glyph-bound canonical document vectors generated in Python.
4. Pure Kotlin `core-model` + `core-canonical` + conformance harness compiled with the installed `kotlinc`.
5. The compiled Kotlin harness was executed and required all Python/Kotlin content/snapshot hashes to agree.
6. Editor reducer add/translate/undo/redo was exercised in the Kotlin harness.

## Not executable in the artifact environment
The container does not include an Android SDK or Gradle installation, so `assembleDebug` and Compose instrumentation tests were not run here. The source tree is pinned for current Android tooling and is intended to sync/build in Android Studio with API 37 installed.

The source archive includes `gradle-wrapper.properties`, but not `gradle-wrapper.jar`; regenerate the wrapper from Android Studio or a local Gradle installation with `gradle wrapper --gradle-version 9.5.0`.
