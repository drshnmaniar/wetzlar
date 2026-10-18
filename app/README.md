# Leica LUX Android app

## Android API and build tool targets

This Pixel-focused build supports Android 16 (API 36) and Android 17 (API 37): the app and
all library modules use `minSdk = 36`, `compileSdk = 37`, and the app uses `targetSdk = 37`.
API 37-only calls must still be guarded at runtime when the app runs on Android 16 / API 36.
The UI opts into edge-to-edge and applies Compose safe-drawing insets, as Android 16 enforces
edge-to-edge for apps targeting API 36 and later.

The project uses Android Gradle Plugin 9.4.0, Gradle 9.6.0, Kotlin 2.4.10, and JDK 17 to
compile against API 37. The repository currently contains the wrapper properties file but not
the Gradle wrapper scripts or wrapper JAR, so a Gradle command-line build requires restoring
those wrapper files or using a compatible installed Gradle/Android Studio setup.

## Pixel device validation

Build and install the app on the target Pixel, grant camera permission, and open **DEVICE PROBE**.
The report shows the active GLES renderer/version and the Camera2 IDs, hardware levels, logical
camera membership, stream sizes, physical aperture metadata, and any advertised `DEPTH16` outputs.
Use **Test DEPTH16** to unbind CameraX, open the advertised Camera2 depth stream, wait for its first
frame, and then rebind the CameraX preview. A failed/open-timeout result is reported in the probe.

The live viewfinder receives CameraX frames through a `SurfaceTexture` and samples them in the GLES
fragment shader. It does not pass preview pixels through a CPU `Bitmap`. The probe is intended to
record what the device actually exposes; it does not assume every Pixel camera publishes depth.

The selected LUT is applied to both the live preview and still JPEG export. Still export decodes a
sampled bitmap capped at approximately 12 megapixels, applies a 64-cube CPU lookup table, and writes
a new JPEG while retaining orientation, date, camera, focal length, and GPS EXIF fields. This keeps
memory bounded but means still capture is not yet an end-to-end GPU path. Aperture controls are UI
placeholders: Pixel camera apertures are fixed, and the depth-blur shader/backend is not connected.
