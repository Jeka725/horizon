# Android GameActivity Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Launch the existing Horizon Rust client on an Android GameActivity surface using winit, while retaining the resource importer.

**Architecture:** Keep Kotlin's importer as the launcher. Add an AndroidX GameActivity backed by `android-activity`, expose the existing Rust game module through the client library, and pass AndroidApp into winit's Android event-loop builder. Resolve prepared resources from app-private storage.

**Tech Stack:** Rust 2024, horizon-client, winit 0.30.13, wgpu 30.0.1, android-activity 0.6, AndroidX GameActivity 4.4.0, Kotlin, Gradle.

**Spec:** `docs/superpowers/specs/2026-11-01-android-game-activity-design.md`

## Global Constraints

- Android native ABI: `aarch64-linux-android` for physical devices and `x86_64-linux-android` for emulator CI.
- Android Activity integration uses `android-activity` GameActivity and winit 0.30.13's explicit AndroidApp event-loop binding.
- Do not enable GameActivity prefab or link the upstream C++ glue.
- Do not claim vanilla `.minecraft` imports are launch-ready unless `horizon-resources.json` exists.
- Desktop launch behavior and tests must remain unchanged.

## Review Focus

- Missing prepared resources: Android must show/log an actionable error rather than panic.
- Activity destroyed/recreated: native entry point and event loop must return/reinitialize cleanly.
- Surface pause/resume: winit must receive lifecycle events and not keep rendering to a stale surface.
- ABI mismatch: APK must contain the matching library for the emulator and physical device.
- Desktop regression: the refactor must preserve the existing desktop `EventLoop::new()` path and binary startup.

---

### Task 1: Expose the existing game entry point from the client library

**Files:**
- Modify: `crates/horizon-client/src/lib.rs`
- Modify: `crates/horizon-client/src/main.rs`
- Test: existing `cargo test -p horizon-client` and `cargo check -p horizon-client`

**Interfaces:**
- Produces: `horizon_client::game::run() -> horizon_client::Result<()>`, used by both desktop binary and Android bridge.

- [ ] **Step 1: Add a library-level compile test or module visibility test** proving `horizon_client::game` is accessible.
- [ ] **Step 2: Run the focused test/check and confirm it fails because the module is private/not exported.**
- [ ] **Step 3: Move the binary's `mod game;` use to `horizon_client::game` and expose `pub mod game;` in the library without duplicating module compilation.**
- [ ] **Step 4: Run `cargo check -p horizon-client` and `cargo test -p horizon-client --lib`.**
- [ ] **Step 5: Commit the refactor.**

### Task 2: Bind both client event-loop paths to AndroidApp

**Files:**
- Modify: `crates/horizon-client/Cargo.toml`
- Modify: `crates/horizon-client/src/game.rs`
- Test: Android target `cargo check -p horizon-client --target aarch64-linux-android`; desktop `cargo test -p horizon-client --lib`

**Interfaces:**
- Produces: `horizon_client::game::run_android(app: android_activity::AndroidApp) -> Result<()>`.
- Desktop continues to call `horizon_client::game::run()`.

- [ ] **Step 1: Add a small event-loop factory test/helper contract for selecting the AndroidApp-backed builder on Android.**
- [ ] **Step 2: Run the Android compile/test and confirm it fails before the Android-specific entry point exists.**
- [ ] **Step 3: Add the Android-only dependency and `winit/platform/android::EventLoopBuilderExtAndroid` path; use it in both `run_loading` and `run_event_loop`.**
- [ ] **Step 4: Keep desktop event-loop construction unchanged under non-Android cfg.**
- [ ] **Step 5: Run Android and desktop checks, then commit.**

### Task 3: Add the GameActivity native entry point

**Files:**
- Modify: `android-native/Cargo.toml`
- Modify: `android-native/src/lib.rs`
- Modify: `android-app/build.gradle.kts`
- Modify: `android-app/src/main/AndroidManifest.xml`
- Create: `android-app/src/main/java/com/horizon/android/HorizonGameActivity.kt`
- Test: Android ARM64 and x86_64 native builds; emulator Activity launch smoke test.

**Interfaces:**
- Produces: exported `android_main(AndroidApp)` which initializes app-private storage and calls `horizon_client::game::run_android(app)`.

- [ ] **Step 1: Add an entry-point smoke test/CI assertion that fails when the cdylib lacks the expected Android entry point.**
- [ ] **Step 2: Run the symbol check and confirm the current JNI-only library fails it.**
- [ ] **Step 3: Add `android-activity` with `game-activity`, the `horizon-client` dependency, Android logger, and the exported `android_main`.**
- [ ] **Step 4: Add the matching AndroidX GameActivity Gradle dependency and manifest metadata; do not enable prefab.**
- [ ] **Step 5: Run both ABI builds and emulator launch test, then commit.**

### Task 4: Connect importer storage and game launch UI

**Files:**
- Modify: `android-app/src/main/java/com/horizon/android/MainActivity.kt`
- Modify: `crates/horizon-client/src/game.rs`
- Test: Kotlin unit/instrumentation test for launch gating; Rust resource-root test.

**Interfaces:**
- MainActivity starts `HorizonGameActivity` only when `filesDir/resources/horizon-resources.json` exists.
- Android client resolves that prepared resource directory after setting its working directory to app-private storage.

- [ ] **Step 1: Write a failing test for launch eligibility with and without the prepared-resource manifest.**
- [ ] **Step 2: Run the test and confirm the missing-manifest case is not handled correctly.**
- [ ] **Step 3: Add the launch button/gating and Android resource-root selection.**
- [ ] **Step 4: Run tests and emulator smoke test for both prepared and missing-resource cases.**
- [ ] **Step 5: Commit.**

### Task 5: Verify lifecycle and deliver the APK

**Files:**
- Modify: `.github/workflows/android-apk.yml`
- Test: emulator lifecycle smoke tests plus all repository checks.

- [ ] **Step 1: Add emulator assertions for Activity start, pause/resume, and clean return on missing resources.**
- [ ] **Step 2: Run the emulator tests and capture any failing lifecycle behavior.**
- [ ] **Step 3: Fix only the failures reproduced by those tests.**
- [ ] **Step 4: Run the full checks, ARM64 build, APK build, and emulator smoke test.**
- [ ] **Step 5: Upload the APK artifact and record the tested commit SHA.**
