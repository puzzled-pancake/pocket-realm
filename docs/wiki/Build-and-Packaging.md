# Build and Packaging

This page explains how the repository becomes one Android application. It is written for readers who want the full project picture, not as a step-by-step developer setup guide.

## One APK, several kinds of software

The final app combines:

- Kotlin and Jetpack Compose for the Android interface and coordination.
- Java and native code from the embedded display system.
- C and C++ for CMaNGOS, MariaDB integration, the display bridge, graphics bridge, and data preparation tools.
- Rust for the optional client tweak patcher.
- Prebuilt, pinned runtime files for Box64, Wine, DXVK, and the optional Vulkan driver.
- SQL migrations assembled from CMaNGOS, Classic-DB, and Playerbots.
- Python build tools that prepare and verify these pieces before Gradle packages them.

## Repository layout in plain language

| Folder | Purpose |
| --- | --- |
| `android` | The Android application, screens, services, tests, packaged assets, and Gradle build. |
| `native/cmangos` | Pinned CMaNGOS Classic source. |
| `native/classic-db` | Pinned starting world database source. |
| `native/playerbots` | Pinned Playerbots source. |
| `native/realm-runtime` | Pocket Realm wrappers that run login and world components as controlled native services. |
| `native/xserver-winlator` | Vendored display, shared memory, input, audio, and Vortek graphics bridge code. |
| `native/vanilla-tweaks` | The pinned Rust client tweak tool. |
| `native/packaging` and `native/pocket-runtime` | Native launch and packaging support used to fit desktop-era components into Android. |
| `schemas` | Machine-readable source, package, migration, and runtime lock information. |
| `tools` | Scripts that build, stage, verify, and package the native and runtime pieces. |
| `docs/wiki` | Human-facing product documentation. |

## Pinned source projects

CMaNGOS, Classic-DB, and Playerbots are Git submodules. A submodule records an exact source revision inside this repository. Updating the upstream project does not silently change a Pocket Realm build.

Other large components are also tied to a version, source revision, archive hash, or package hash. Examples include Wine, MariaDB, Winlator display code, DXVK, and Turnip.

## Staging

Staging means taking a source build or verified upstream package and turning it into the exact folder layout expected inside the APK.

The staging tools can:

- Check a downloaded archive's SHA-256 fingerprint.
- Reject unexpected archive paths or file types.
- Extract only the required runtime closure.
- Rename native executables into Android-packaged native library names where required by Android installation rules.
- Generate manifests containing file names, sizes, hashes, processor type, and package identity.
- Check that every native library dependency is present.
- Build renderer and Vulkan driver catalogues used by Settings.
- Assemble compressed database migrations in their required order.

## Native compilation

CMake and the Android NDK compile the native Pocket Realm wrappers and the selected upstream code for Android. The login and world components are library-backed but keep separate Android process boundaries.

MariaDB differs by processor lane. The main ARM64 route packages the Termux Android MariaDB 12.3.2 runtime. The x86-64 validation lane uses the separately staged MariaDB 11.5.2 glibc runtime.

## Android assembly

Gradle builds the Kotlin application, Compose screens, private services, AIDL control interfaces, tests, native libraries, and staged assets into the Android package.

The current application identity is `com.pocketrealm`. It supports Android API 26 and later and targets API 35. The app is landscape-oriented, disables Android automatic backup, and declares foreground service types for the long-running realm and finite import work.

## Checks before packaging

The build contains fail-closed checks for important packaged material. Depending on the component, it verifies:

- The expected processor type.
- Exact file size and hash.
- Required manifest fields.
- Native dependency closure.
- Correct runtime provider identity.
- Correct DXVK and Vulkan package identity.
- Complete database migration ledgers.
- Supported client and tweak signatures.
- Required Android assets for each enabled route.

Fail closed means the build or launch stops when proof is missing. It does not quietly package an incomplete substitute.

## Why normal players do not manage these parts

The build process turns all allowed choices into closed catalogues inside the app. A player selects a named DXVK package, Vulkan driver, bot profile, or display profile. The player is not asked to supply a library path, shell command, SQL script, or Wine environment.

This keeps the everyday interface understandable and keeps the runtime supervisor responsible for known combinations.

## Tests and physical qualification

The repository includes local unit tests, Android instrumented tests, native tests, package checks, and scripts for testing specific runtime closures. Physical device testing is still important because graphics drivers, controller reports, thermal behaviour, Android process rules, and sound timing differ from desktop development machines.

A feature should be documented as working only when the current code, packaged assets, and a current verified build agree.

## Related pages

- [Projects and technologies used](Projects-and-Technologies.md)
- [Game client, graphics, display, and sound](Game-Client-Graphics-and-Sound.md)
- [Runtime supervision and recovery](Runtime-Supervision-and-Recovery.md)
- [Data, storage, and privacy](Data-Storage-and-Privacy.md)
