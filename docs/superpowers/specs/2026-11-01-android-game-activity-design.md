# Android GameActivity Integration Design

## Intent

Turn the existing Android resource-import shell into an entry point for the existing Horizon Rust game client. The Android app should keep its resource importer, launch the native game on a real Android surface, and reuse Horizon's current winit/wgpu client rather than creating a second renderer.

## Current State

- `android-app` is a Kotlin `Activity` that imports prepared Horizon resources or a Minecraft `.minecraft` folder into app-private storage.
- `android-native` is a small JNI `cdylib`; it currently exposes only `nativeEngineStatus()`.
- `crates/horizon-client` already contains the complete Rust client and uses winit 0.30.13 plus wgpu 30.0.1, but the game loop lives in the binary's private `game.rs` module.
- The Android workflow cross-compiles the client for ARM64, but that artifact is not the Android Activity's running game.
- The client expects prepared files containing `horizon-resources.json`. Importing a vanilla `.minecraft` directory currently indexes the source files; it does not create that prepared manifest.

## Goals

1. Start the existing Horizon client from Android using an Android-owned window/surface and winit's Android event loop.
2. Preserve the current resource-import screen and add a distinct game Activity.
3. Use the imported prepared-resource directory in app-private storage as the client resource root.
4. Keep Android lifecycle, surface recreation, and event polling under `android-activity`/winit rather than manually driving a JNI render loop.
5. Keep ARM64 as the physical-device ABI and use an x86_64 emulator ABI for automated smoke tests.

## Non-goals

- Reimplementing the renderer or world simulation in Kotlin.
- Embedding a desktop Java Runtime; Horizon is a Rust reimplementation.
- Claiming that importing a vanilla `.minecraft` folder alone makes it launchable. A separate resource-preparation stage remains required.
- Shipping touch controls, multiplayer authentication, audio, or production Play Store packaging in the first integration milestone.

## Approaches

### A. Keep a normal Activity and call the game through JNI

This cannot provide winit with the `AndroidApp` and native surface lifecycle it requires. It would require a custom SurfaceView bridge and manual lifecycle/event plumbing. Rejected.

### B. Use Android NativeActivity

This is a direct fit for native Rust and winit, but has less convenient text-input integration for the existing game UI. Viable fallback.

### C. Keep the importer Activity and launch a dedicated GameActivity

Use `androidx.games:games-activity:4.4.0` with `android-activity`'s `game-activity` feature. The native entry point receives `AndroidApp`; winit's `EventLoopBuilderExtAndroid::with_android_app` creates the event loop and wgpu uses the resulting native window. This keeps the importer intact and supports text input. **Recommended.**

## Architecture

- `MainActivity` remains the importer and starts `HorizonGameActivity` only when `filesDir/resources/horizon-resources.json` exists.
- `HorizonGameActivity` extends the AndroidX GameActivity class and declares the native library name in the manifest.
- `android-native` remains the app's `cdylib`, adds `android-activity` with `game-activity`, and exports `android_main(AndroidApp)`.
- The native entry point sets the app-private working directory, initializes Android logging, and calls an Android-specific entry point in `horizon-client`.
- `horizon-client` exposes its game module from the library so both the desktop binary and Android `cdylib` use the same implementation.
- On Android, both loading-screen and gameplay event loops are built with the supplied `AndroidApp`; desktop keeps `EventLoop::new()`.
- Android's default resource root resolves to the imported prepared resources directory. Missing prepared resources must produce a visible/logged actionable error, not a crash or silent fallback.

## Constraints and Risks

- winit 0.30.13 requires an explicit `AndroidApp` via `EventLoopBuilderExtAndroid::with_android_app`.
- `android-activity`'s GameActivity integration requires the matching AndroidX dependency and manifest configuration; do not enable prefab or link the upstream C++ glue.
- The Rust game module is currently private to the binary. Exposing it must not change desktop startup behavior or duplicate the module.
- The current `.minecraft` import path is not equivalent to Horizon's prepared-resource format; do not mark it as launch-ready.
- Android surface loss/recreation and returning from the game Activity must be tested on an emulator and then on a physical ARM64 device.

## Acceptance

- The APK contains the ARM64 native library and installs on an ARM64 Android device.
- The game Activity enters the Rust `android_main` path and constructs winit's Android event loop.
- With prepared Horizon resources present, the existing client reaches its title/loading UI on the Android surface.
- Without prepared resources, the Activity exits cleanly and records a useful error.
- Desktop client builds and existing tests remain unchanged.
