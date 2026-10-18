# Wetzlar for Android

Wetzlar is a Pixel-focused camera app prototype built with Kotlin, CameraX, Camera2,
Jetpack Compose, and OpenGL ES 3.0. The current goal is to build and validate the camera
pipeline on Android 16 and 17 devices before expanding the image-processing features.

> **Prototype status:** this project is not a finished camera app. Some controls are UI-only,
> device capabilities vary, and several processing modules are scaffolding rather than wired
> end-to-end features. See [Current limitations](#current-limitations).

## What works in the current app

- CameraX preview frames are sent to a `SurfaceTexture` and sampled by an OpenGL ES shader.
  The live preview path does not copy each frame through a CPU bitmap.
- Packaged `.cube` LUTs can be selected for live preview and still-photo output.
- Still photos are captured and saved to `Pictures/Wetzlar`; the selected LUT is applied to a
  sampled bitmap before the JPEG is written. Orientation and selected EXIF fields are retained.
- **DEVICE PROBE** reports camera and GLES capabilities. **Test DEPTH16** tries an advertised
  Camera2 depth stream and reports whether a frame arrives.
- Compose provides the camera overlay, lens and look selectors, exposure controls, and shutter UI.

## Android and build requirements

- Android 16 (API 36) or Android 17 (API 37) device. The app uses `minSdk = 36`.
- Android SDK platform API 37 to compile the project; the app uses `targetSdk = 37`.
- JDK 17, Android Gradle Plugin 9.4.0, Gradle 9.6.0, and Kotlin 2.4.10.
- Android Studio with the Android SDK, or a command-line Android SDK installation.

The repository has `gradle/wrapper/gradle-wrapper.properties`, but it does not yet include the
Gradle wrapper scripts or wrapper JAR. Until those files are added, use an installed Gradle 9.6.0.

From the repository root, build the debug APK with:

```shell
gradle :app:assembleDebug
```

Install it on a connected device with:

```shell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Grant camera access when prompted, then open **DEVICE PROBE** to collect the device report.

## Pixel validation

1. Confirm the report identifies the device, Android API level, GLES renderer, and version.
2. Confirm the preview starts and responds to rotation and app pause/resume.
3. Select each available look and confirm it appears in the preview.
4. Capture a photo and confirm the saved JPEG opens from `Pictures/Wetzlar` with the selected look.
5. Check the report for cameras that advertise `DEPTH16`, then run **Test DEPTH16**. The helper
   unbinds CameraX while it opens a direct Camera2 depth session, then the app rebinds the preview.
6. Record unsupported streams, session errors, missing frames, and preview or capture performance.

The depth report is device-specific: a Pixel model or camera that does not advertise the required
capability cannot provide a `DEPTH16` stream through this path.

## Project layout

```text
.
├── app/                    Compose UI, app entry point, Pixel probe, photo export
├── core/
│   ├── camera/             CameraX control, manual settings, Camera2 DEPTH16 reader
│   ├── lens-engine/        GLES renderer, LUT parsing, render pipeline and shader assets
│   ├── ml-depth/           Depth-processing adapter and fallback
│   ├── lpc/                Perspective geometry and orientation tracking prototype
│   └── ble-grip/           BLE scanning/GATT support with profile-specific decoder hook
├── gradle/                 Version catalog and Gradle wrapper configuration
├── build.gradle.kts        Root plugin configuration
└── settings.gradle.kts     Project and module registration
```

The Android application ID and Kotlin namespace are `com.wetzlar.camera`.

## Current limitations

- The aperture selector changes a displayed value only. Phone camera apertures are fixed and are
  not controlled by the UI.
- The live preview uses OpenGL for LUT display, but still-photo LUT processing currently uses the
  CPU and is capped at approximately 12 megapixels. The still capture path is not GPU-only.
- Depth of field and lens optical effects are not connected to the preview or photo export.
- The depth adapter does not include a neural model. Its current depth upscaling path is bilinear.
- BLE hardware support needs a verified device profile, service/characteristic UUIDs, and packet
  decoder before a specific grip can be supported.
- Perspective control is a geometric prototype and is not wired into the camera render/export path.
- Camera2 manual controls and depth stream combinations vary by device and camera. Unsupported
  controls and stream failures should be treated as normal probe results.

## Modules

The app is assembled from the `:core:camera`, `:core:lens-engine`, `:core:ml-depth`, `:core:lpc`,
and `:core:ble-grip` libraries. Each library has its own Gradle configuration; the app module owns
runtime permissions and connects the modules to the Compose UI.
