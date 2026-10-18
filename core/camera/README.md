# Camera module

`CameraXController` binds still capture and a CameraX preview to either a `PreviewView` or a custom
`Preview.SurfaceProvider`. The app's `GLCameraView` supplies an EGL-backed `SurfaceTexture` surface
to the custom provider.

`Camera2DepthReader` queries `REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT` and
`SCALER_STREAM_CONFIGURATION_MAP` for `ImageFormat.DEPTH16`, then opens an `ImageReader` session and
decodes depth in millimeters plus the DEPTH16 confidence bits. Devices, including Pixel models, vary
in their advertised depth outputs and valid stream combinations; callers must use the query results
and handle `Unsupported`/`Failed` states. DEPTH16 output may stall and is not available on every
camera.

CameraX and a direct Camera2 session cannot own the same camera at once. Stop/unbind
`CameraXController` before `Camera2DepthReader.start`. To show color during depth capture, pass a
GLCameraView-backed `Surface` only when `depthIsExclusive` is false; the helper will report stream
configuration failures rather than silently dropping depth.

Example:

```kotlin
val reader = Camera2DepthReader(context, onFrame = { depthFrame -> /* consume arrays */ })
val support = reader.queryDepthSupport().firstOrNull()
if (support != null) {
    cameraXController.unbind()
    reader.start(support.cameraId, previewSurface = glCameraView.createCamera2Surface())
}
```
