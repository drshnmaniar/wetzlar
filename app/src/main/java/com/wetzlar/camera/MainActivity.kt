package com.wetzlar.camera

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.media.MediaScannerConnection
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.wetzlar.camera.camera.CameraSessionState
import com.wetzlar.camera.camera.CameraXController
import com.wetzlar.camera.camera.CaptureResult
import com.wetzlar.camera.lensengine.LutColorFilter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wetzlar.camera.camera.ManualSettings
import com.wetzlar.camera.lensengine.model.WetzlarLens
import com.wetzlar.camera.lensengine.model.WetzlarLook

private val WetzlarRed = Color(0xFFD71920)
private val ScreenBlack = Color(0xFF101010)
private val PanelBlack = Color(0xFF1A1A1A)
private val MutedText = Color(0xFF969696)
private val SimulatedApertures = listOf(1.2f, 1.4f, 1.8f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f, 11.0f, 16.0f)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                WetzlarCameraApp()
            }
        }
    }
}

@Composable
private fun WetzlarCameraApp() {
    var selectedLens by remember { mutableStateOf(WetzlarLens.PRIME_35) }
    var selectedAperture by remember { mutableStateOf(1.4f) }
    var selectedLook by remember { mutableStateOf(WetzlarLook.CLASSIC) }
    var settings by remember { mutableStateOf(ManualSettings()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = context as LifecycleOwner
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraController = remember(context) { CameraXController(context) }
    val cameraSession by cameraController.state.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var cameraPermissionMessage by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
        cameraPermissionMessage = if (granted) null else "Camera permission is required to use the viewfinder."
    }
    var previewView by remember { mutableStateOf<GLCameraView?>(null) }
    var cameraStatus by remember { mutableStateOf<String?>(null) }
    var manualControlStatus by remember { mutableStateOf<String?>(null) }
    var lutStatus by remember { mutableStateOf<String?>(null) }
    var loadedLut by remember { mutableStateOf<LutColorFilter?>(null) }
    var diagnostics by remember { mutableStateOf<String?>(null) }
    var depthTestRunning by remember { mutableStateOf(false) }

    LaunchedEffect(hasCameraPermission, previewView, cameraController) {
        if (hasCameraPermission && previewView != null) {
            cameraStatus = null
            cameraController.bind(lifecycleOwner, previewView!!.previewSurfaceProvider)
        } else {
            cameraController.unbind()
        }
    }
    DisposableEffect(cameraController) {
        onDispose { cameraController.unbind() }
    }

    DisposableEffect(previewView, lifecycleOwner) {
        val glView = previewView
        if (glView == null) onDispose { }
        else {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> glView.onResume()
                    Lifecycle.Event.ON_PAUSE -> glView.onPause()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) glView.onResume()
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                glView.onPause()
            }
        }
    }

    LaunchedEffect(previewView, selectedLook) {
        val glView = previewView ?: return@LaunchedEffect
        lutStatus = "Loading ${selectedLook.title} look…"
        loadedLut = null
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("luts/${selectedLook.lutFileName}").use(LutColorFilter::parseCube)
            }
        }
        loaded.onSuccess { filter ->
            loadedLut = filter
            glView.setLut(filter, selectedLook.defaultIntensity)
            lutStatus = null
        }.onFailure { error ->
            loadedLut = null
            glView.setLut(LutColorFilter.identity(), 0f)
            lutStatus = "Could not load look: ${error.localizedMessage ?: "invalid LUT"}"
        }
    }

    LaunchedEffect(settings, cameraSession, cameraController) {
        if (cameraSession is CameraSessionState.Ready) {
            val result = cameraController.applyManualSettings(settings)
            manualControlStatus = result.unsupported.entries
                .takeIf { it.isNotEmpty() }
                ?.joinToString(separator = " · ") { (control, reason) ->
                    "${control.displayName()}: $reason"
                }
        } else {
            manualControlStatus = null
        }
    }

    val showDiagnostics: () -> Unit = {
        if (!hasCameraPermission) {
            diagnostics = "Grant camera permission first, then run the device probe."
        } else {
            diagnostics = "Reading camera and OpenGL capabilities…"
            val glInfo = previewView?.graphicsInfo ?: "GLSurfaceView has not created an EGL context yet."
            coroutineScope.launch {
                diagnostics = withContext(Dispatchers.IO) {
                    runCatching { PixelCameraProbe.collect(context, glInfo) }
                        .getOrElse { "Probe failed: ${it.javaClass.simpleName}: ${it.localizedMessage}" }
                }
            }
        }
    }
    val runDepthTest: () -> Unit = {
        val glView = previewView
        if (glView == null || !hasCameraPermission) {
            diagnostics = "Start the camera preview and grant camera permission before testing DEPTH16."
        } else if (!depthTestRunning) {
            depthTestRunning = true
            cameraController.unbind()
            coroutineScope.launch {
                val depthResult = withContext(Dispatchers.IO) {
                    runCatching { PixelCameraProbe.testDepthStream(context) }
                        .getOrElse { "DEPTH16 failed: ${it.javaClass.simpleName}: ${it.localizedMessage}" }
                }
                diagnostics = (diagnostics.orEmpty() + "\n\nSTREAM TEST\n" + depthResult)
                cameraController.bind(lifecycleOwner, glView.previewSurfaceProvider)
                depthTestRunning = false
            }
        }
    }

    val capturePhoto: () -> Unit = {
        if (cameraSession !is CameraSessionState.Ready) {
            cameraStatus = "Camera is not ready yet."
        } else {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val captureFile = File(context.cacheDir, "WETZLAR_$timestamp.jpg")
            cameraStatus = "Capturing…"
            cameraController.capture(captureFile) { result ->
                when (result) {
                    is CaptureResult.Failure -> {
                        captureFile.delete()
                        cameraStatus = "Capture failed: ${result.message}"
                    }
                    is CaptureResult.Saved -> {
                        val captureLut = loadedLut
                        val captureIntensity = selectedLook.defaultIntensity
                        cameraStatus = if (captureLut == null) "Saving photo without the selected look…" else "Applying look and saving…"
                        coroutineScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    if (captureLut != null) {
                                        LutJpegProcessor.apply(result.file, captureLut, captureIntensity)
                                    }
                                    copyCaptureToMediaStore(context, result.file, timestamp)
                                }
                                cameraStatus = if (captureLut == null) {
                                    "Photo saved without a look to Pictures/Wetzlar."
                                } else {
                                    "Filtered photo saved to Pictures/Wetzlar (up to 12 MP)."
                                }
                            } catch (error: Exception) {
                                cameraStatus = "Could not save photo: ${error.localizedMessage ?: "storage error"}"
                            } finally {
                                result.file.delete()
                            }
                        }
                    }
                }
            }
        }
    }

    var captureAfterStoragePermission by remember { mutableStateOf(false) }
    val storagePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) captureAfterStoragePermission = true
        else cameraStatus = "Storage permission is required to save photos on this Android version."
    }
    LaunchedEffect(captureAfterStoragePermission) {
        if (captureAfterStoragePermission) {
            captureAfterStoragePermission = false
            capturePhoto()
        }
    }
    val requestCapture: () -> Unit = {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            capturePhoto()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = ScreenBlack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Header(onDiagnostics = showDiagnostics)
            CameraViewfinder(
                lens = selectedLens,
                look = selectedLook,
                hasPermission = hasCameraPermission,
                status = cameraStatus ?: (cameraSession as? CameraSessionState.Error)?.message
                    ?: manualControlStatus ?: cameraPermissionMessage ?: lutStatus,
                isStarting = hasCameraPermission && cameraSession is CameraSessionState.Binding,
                onPermissionRequest = { permissionLauncher.launch(android.Manifest.permission.CAMERA) },
                onPreviewViewCreated = { previewView = it }
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("LENS")
                ChoiceRow(
                    items = WetzlarLens.entries,
                    selected = selectedLens,
                    label = { "${it.focalLengthMm} mm" },
                    onSelected = {
                        selectedLens = it
                    }
                )
                Text(
                    text = selectedLens.title,
                    color = MutedText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("APERTURE")
                ChoiceRow(
                    items = SimulatedApertures,
                    selected = selectedAperture,
                    label = { value -> "f/${String.format(Locale.US, "%.1f", value).removeSuffix(".0")}" },
                    onSelected = { selectedAperture = it },
                )
                Text(
                    text = "DISPLAY ONLY · NO PHONE IRIS OR DEPTH BLUR IS WIRED YET",
                    color = MutedText,
                    fontSize = 9.sp,
                    letterSpacing = 0.7.sp,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("LOOK")
                ChoiceRow(
                    items = WetzlarLook.entries.filterNot { it.isPaid },
                    selected = selectedLook,
                    label = { it.abbreviatedTitle },
                    onSelected = { selectedLook = it }
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("EXPOSURE")
                ExposureControls(settings = settings, onSettingsChanged = {
                    settings = it
                    cameraStatus = null
                })
            }

            Spacer(modifier = Modifier.weight(1f))
            ShutterButton(enabled = hasCameraPermission && cameraSession is CameraSessionState.Ready, onClick = requestCapture)
        }
    }
    diagnostics?.let { report ->
        AlertDialog(
            onDismissRequest = { diagnostics = null },
            title = { Text("Device probe") },
            text = {
                Text(
                    text = report,
                    modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = runDepthTest, enabled = !depthTestRunning) {
                        Text(if (depthTestRunning) "Testing…" else "Test DEPTH16")
                    }
                    TextButton(onClick = { diagnostics = null }) { Text("Close") }
                }
            },
        )
    }
}

private fun copyCaptureToMediaStore(context: android.content.Context, source: File, timestamp: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val directory = File(pictures, "Wetzlar")
        if (!directory.exists() && !directory.mkdirs()) error("Could not create Pictures/Wetzlar")
        val destination = File(directory, "WETZLAR_$timestamp.jpg")
        source.inputStream().use { input -> destination.outputStream().use(input::copyTo) }
        MediaScannerConnection.scanFile(context, arrayOf(destination.absolutePath), arrayOf("image/jpeg"), null)
        return
    }
    val values = android.content.ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "WETZLAR_$timestamp.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Wetzlar")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val destination = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: error("MediaStore could not create the photo entry")
    try {
        val output = resolver.openOutputStream(destination, "w") ?: error("Could not open photo output")
        source.inputStream().use { input ->
            output.use { stream -> input.copyTo(stream) }
        }
        val published = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        resolver.update(destination, published, null, null)
    } catch (error: Exception) {
        resolver.delete(destination, null, null)
        throw error
    }
}

private fun com.wetzlar.camera.camera.ManualControl.displayName(): String = when (this) {
    com.wetzlar.camera.camera.ManualControl.ALL -> "Camera"
    com.wetzlar.camera.camera.ManualControl.ISO -> "ISO"
    com.wetzlar.camera.camera.ManualControl.SHUTTER -> "Shutter"
    com.wetzlar.camera.camera.ManualControl.EXPOSURE_COMPENSATION -> "EV"
    com.wetzlar.camera.camera.ManualControl.FOCUS -> "Focus"
    com.wetzlar.camera.camera.ManualControl.WHITE_BALANCE -> "WB"
}

@Composable
private fun Header(onDiagnostics: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(WetzlarRed)
            )
            Spacer(modifier = Modifier.width(9.dp))
            Text("WETZLAR", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(modifier = Modifier.width(7.dp))
            Text("CAMERA", color = MutedText, fontSize = 11.sp, letterSpacing = 1.5.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDiagnostics) { Text("DEVICE PROBE", color = MutedText, fontSize = 10.sp) }
            Text("PHOTO", color = MutedText, fontSize = 11.sp, letterSpacing = 1.5.sp)
        }
    }
}

@Composable
private fun CameraViewfinder(
    lens: WetzlarLens,
    look: WetzlarLook,
    hasPermission: Boolean,
    status: String?,
    isStarting: Boolean,
    onPermissionRequest: () -> Unit,
    onPreviewViewCreated: (GLCameraView) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF4A4A45), Color(0xFF292A27), Color(0xFF171817))
                )
            )
            .padding(16.dp)
    ) {
        if (hasPermission) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    GLCameraView(context).also(onPreviewViewCreated)
                },
                update = { onPreviewViewCreated(it) }
            )
        }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (hasPermission && status == null) 0.10f else 0.62f)))
        // Camera framing marks remain visible above the live preview.
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ReticleCorner(top = true, start = true)
                ReticleCorner(top = true, start = false)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ReticleCorner(top = false, start = true)
                ReticleCorner(top = false, start = false)
            }
        }
        Column(modifier = Modifier.align(Alignment.TopStart)) {
            Text(if (hasPermission) "VIEWFINDER" else "CAMERA ACCESS", color = Color.White.copy(alpha = 0.78f), fontSize = 10.sp, letterSpacing = 1.5.sp)
            Spacer(modifier = Modifier.height(5.dp))
            when {
                !hasPermission -> {
                    Text("Allow camera access to start the viewfinder.", color = Color.White.copy(alpha = 0.84f), fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onPermissionRequest, colors = ButtonDefaults.buttonColors(containerColor = WetzlarRed)) {
                        Text("Allow camera", color = Color.White)
                    }
                }
                status != null -> Text(status, color = Color.White, fontSize = 12.sp)
                isStarting -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Text("Starting camera…", color = Color.White.copy(alpha = 0.84f), fontSize = 12.sp)
                }
            }
        }
        Row(
            modifier = Modifier.align(Alignment.BottomStart),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HudPill("${lens.focalLengthMm} mm")
            HudPill(look.abbreviatedTitle)
        }
        Text("AUTO", modifier = Modifier.align(Alignment.BottomEnd), color = Color.White, fontSize = 11.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun ReticleCorner(top: Boolean, start: Boolean) {
    val horizontal = Modifier.width(24.dp).height(2.dp).background(Color.White.copy(alpha = 0.52f))
    val vertical = Modifier.width(2.dp).height(24.dp).background(Color.White.copy(alpha = 0.52f))
    Column {
        if (top) {
            Row {
                if (start) { Spacer(horizontal); Spacer(vertical) } else { Spacer(vertical); Spacer(horizontal) }
            }
        } else {
            Row {
                if (start) { Spacer(vertical); Spacer(horizontal) } else { Spacer(horizontal); Spacer(vertical) }
            }
        }
    }
}

@Composable
private fun HudPill(label: String) {
    Surface(color = Color.Black.copy(alpha = 0.46f), shape = RoundedCornerShape(20.dp)) {
        Text(label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Color.White, fontSize = 10.sp)
    }
}

@Composable
private fun <T> ChoiceRow(
    items: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit
) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items.size) { index ->
            val item = items[index]
            FilterChip(
                selected = item == selected,
                onClick = { onSelected(item) },
                label = { Text(label(item), fontSize = 11.sp, maxLines = 1) },
                border = BorderStroke(1.dp, if (item == selected) WetzlarRed else Color(0xFF393939)),
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                    selectedContainerColor = WetzlarRed.copy(alpha = 0.18f),
                    selectedLabelColor = Color.White,
                    labelColor = MutedText
                )
            )
        }
    }
}

@Composable
private fun ExposureControls(settings: ManualSettings, onSettingsChanged: (ManualSettings) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        ExposureValue(
            modifier = Modifier.weight(1f),
            title = "ISO",
            value = settings.isoDisplay,
            onClick = {
                val values = listOf(null, 100, 200, 400, 800, 1600, 3200)
                val next = values[(values.indexOf(settings.iso) + 1).mod(values.size)]
                onSettingsChanged(settings.copy(iso = next))
            }
        )
        ExposureValue(
            modifier = Modifier.weight(1f),
            title = "SHUTTER",
            value = settings.shutterSpeedDisplay,
            onClick = {
                val values = listOf(null, 1_000_000_000L / 250, 1_000_000_000L / 125, 1_000_000_000L / 60, 1_000_000_000L / 30)
                val next = values[(values.indexOf(settings.exposureTimeNs) + 1).mod(values.size)]
                onSettingsChanged(settings.copy(exposureTimeNs = next))
            }
        )
        ExposureValue(
            modifier = Modifier.weight(1f),
            title = "EV",
            value = settings.evDisplay,
            onClick = {
                val currentThirds = (settings.exposureCompensationEv * 3f).roundToInt()
                val next = if (currentThirds >= 9) -9 else currentThirds + 1
                onSettingsChanged(settings.copy(exposureCompensationEv = next / 3f))
            }
        )
    }
}

@Composable
private fun ExposureValue(modifier: Modifier, title: String, value: String, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(PanelBlack)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, color = MutedText, fontSize = 9.sp, letterSpacing = 1.sp)
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = MutedText, fontSize = 10.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = ScreenBlack)
        ) {
            Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.White))
        }
    }
}
