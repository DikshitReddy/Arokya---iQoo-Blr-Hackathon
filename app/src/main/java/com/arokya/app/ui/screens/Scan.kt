package com.arokya.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.arokya.app.data.Meal
import com.arokya.app.data.MealAnalysis
import com.arokya.app.data.ScannedItem
import com.arokya.app.data.Store
import com.arokya.app.engine.RuleEngine
import com.arokya.app.ml.ChatTurn
import com.arokya.app.ml.InferenceStat
import com.arokya.app.ml.InferenceStats
import com.arokya.app.ml.Ml
import com.arokya.app.ml.labFlagsClause
import com.arokya.app.ml.structuredLanguageDirective
import com.arokya.app.ui.theme.Ar
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed class ScanPhase {
    data object Preview : ScanPhase()
    data object Capturing : ScanPhase()
    data class Failed(val message: String) : ScanPhase()
}

/** The two things this camera screen can do — see the tab row at the top. */
enum class ScanTab(val emoji: String, val label: String, val hint: String, val analyzingTitle: String) {
    Meal("🍽️", "Scan a Prepared Meal", "Point at your plate", "🔍 Analyzing your meal…"),
    Ingredients(
        "🥕", "Scan Ingredients / Groceries", "Point at your fridge or grocery items",
        "🍎 Analyzing the items in your image…"
    ),
}

/**
 * Real camera scan: a live CameraX preview behind an animated viewfinder.
 * Capturing a photo hands it straight off (see [onCaptured]) to a dedicated
 * full-screen analysis experience — [AnalyzingScreen] — rather than running
 * the AI call here on top of the live camera feed.
 */
@Composable
fun ScanScreen(
    onBack: () -> Unit,
    onCaptured: (ScanTab, ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var selectedTab by remember { mutableStateOf(ScanTab.Meal) }
    var phase by remember { mutableStateOf<ScanPhase>(ScanPhase.Preview) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    fun capture() {
        val ic = imageCapture ?: return
        phase = ScanPhase.Capturing
        ic.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val jpeg = try {
                        image.toUprightJpegBytes()
                    } finally {
                        image.close()
                    }
                    phase = ScanPhase.Preview
                    onCaptured(selectedTab, jpeg)
                }

                override fun onError(exception: ImageCaptureException) {
                    phase = ScanPhase.Failed(exception.message ?: "Camera capture failed.")
                }
            }
        )
    }

    Column(Modifier.fillMaxSize().background(Ar.Cream)) {
        ArHeader("Camera scan", onBack = onBack)
        ScanTabRow(
            selected = selectedTab,
            enabled = phase == ScanPhase.Preview,
            onSelect = { selectedTab = it },
        )
        Spacer(Modifier.height(12.dp))

        Box(
            Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF14171C)),
            contentAlignment = Alignment.Center
        ) {
            if (hasPermission) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            // SurfaceView (the default) can render solid black when layered
                            // under other Compose content (our scan-line/corner overlay).
                            // TextureView compositing is the standard fix.
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val capture = ImageCapture.Builder()
                                // Lower resolution capture = faster shutter AND a smaller
                                // source image, on top of the downscale in toUprightJpegBytes.
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .build()
                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    capture
                                )
                                imageCapture = capture
                            } catch (e: Exception) {
                                Log.e("ArokyaScan", "Camera failed to bind — is it held by another app?", e)
                                phase = ScanPhase.Failed(
                                    "Couldn't access the camera (${e.message}). " +
                                            "Close any other app using the camera and try again."
                                )
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    }
                )
                ScanViewfinderOverlay()

                when (val p = phase) {
                    is ScanPhase.Capturing -> ScanStatusBanner("Capturing…")
                    is ScanPhase.Failed -> ScanErrorBanner(p.message) { phase = ScanPhase.Preview }
                    ScanPhase.Preview -> Text(
                        selectedTab.hint,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 18.dp)
                    )
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.CameraAlt, null, tint = Color.White.copy(alpha = 0.6f))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Camera access needed to scan",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Grant access",
                        color = Ar.Teal, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .background(Color.White, RoundedCornerShape(999.dp))
                            .clickable { permissionLauncher.launch(Manifest.permission.CAMERA) }
                            .padding(horizontal = 18.dp, vertical = 10.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        ShutterButton(
            enabled = hasPermission && phase == ScanPhase.Preview,
            busy = phase is ScanPhase.Capturing,
            onClick = ::capture,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(72.dp)
                .border(3.dp, if (enabled) Ar.Teal else Ar.Muted, CircleShape)
                .padding(6.dp)
                .background(if (enabled) Ar.Teal else Ar.Muted, CircleShape)
                .clickable(enabled = enabled) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            if (busy) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
            }
        }
    }
}

/** Cosmetic step captions shown while the real [Ml] call is in flight — see [AnalyzingScreen]. */
private fun analyzingSteps(mode: ScanTab): List<String> = when (mode) {
    ScanTab.Meal -> listOf(
        "Analyzing image…",
        "Identifying food items…",
        "Estimating portion sizes…",
        "Calculating calories & macros…",
        "Checking against your fitness goals…",
        "Preparing personalized suggestions…",
    )
    ScanTab.Ingredients -> listOf(
        "Analyzing image…",
        "Identifying ingredients…",
        "Checking what's already in your pantry…",
        "Matching against your diet plan…",
        "Preparing recipe ideas…",
    )
}

/**
 * Full-screen "the AI is actively looking at your photo" experience — shown
 * instead of overlaying analysis status on the live camera feed. The real
 * [Ml.mealAnalyzer]/[Ml.vision] call runs here; the step captions cycling
 * underneath are cosmetic (there's no real per-step signal from a single
 * model call), but the result itself is always the live one — on failure
 * this shows a real error, never invented data.
 */
@Composable
fun AnalyzingScreen(
    mode: ScanTab,
    jpegBytes: ByteArray,
    onMealAnalyzed: (MealAnalysis, InferenceStat?) -> Unit,
    onResults: (List<ScannedItem>, InferenceStat?) -> Unit,
    onBack: () -> Unit,
) {
    val steps = remember(mode) { analyzingSteps(mode) }
    var stepIndex by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(retryKey) {
        error = null
        stepIndex = 0
        val cycleJob = launch {
            while (isActive) {
                delay(1500)
                if (stepIndex < steps.size - 1) stepIndex++
            }
        }
        try {
            // InferenceStats.last is read right after each call returns, so the
            // result screen shows the measurement for THIS inference.
            when (mode) {
                ScanTab.Meal -> {
                    val analysis = Ml.mealAnalyzer.analyzeMeal(jpegBytes, Store.buildContext())
                    onMealAnalyzed(analysis, InferenceStats.last)
                }
                ScanTab.Ingredients -> {
                    val items = Ml.vision.detectIngredients(
                        jpegBytes, Store.profile.dietTags, Store.profile.goal
                    )
                    onResults(items, InferenceStats.last)
                }
            }
        } catch (e: Exception) {
            error = e.message ?: "Couldn't analyze that photo."
        } finally {
            cycleJob.cancel()
        }
    }

    Box(Modifier.fillMaxSize().background(Ar.Cream)) {
        // Animated fruit-salad GIF, faded into the background as an ambient
        // loading motion behind the step list. Only while actually analyzing —
        // it disappears on the error state so nothing distracts from the fix.
        if (error == null) {
            AnalyzingBackground(mode.emoji)
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (error != null) {
                Icon(Icons.Filled.PriorityHigh, null, tint = Ar.Orange, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(16.dp))
                Text(
                    "Couldn't finish analyzing", fontSize = 19.sp, fontWeight = FontWeight.Bold,
                    color = Ar.Navy, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    error!!, fontSize = 14.sp, color = Ar.Slate, lineHeight = 20.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
                ArPrimaryButton("Try again", onClick = { retryKey++ })
                Spacer(Modifier.height(10.dp))
                ArSecondaryButton("Retake photo", onClick = onBack)
            } else {
                val pulse = rememberInfiniteTransition(label = "analyzingPulse")
                val scale by pulse.animateFloat(
                    initialValue = 0.92f,
                    targetValue = 1.08f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(950, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "scale"
                )
                Box(
                    Modifier
                        .size(96.dp)
                        .scale(scale)
                        .background(Ar.TealChipBg, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(mode.emoji, fontSize = 40.sp)
                }
                Spacer(Modifier.height(22.dp))
                Text(
                    mode.analyzingTitle, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                    color = Ar.Navy, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
                Column(Modifier.fillMaxWidth()) {
                    steps.forEachIndexed { index, step ->
                        val state = when {
                            index < stepIndex -> StepState.Done
                            index == stepIndex -> StepState.Active
                            else -> StepState.Pending
                        }
                        AnalyzingStepRow(step, state)
                        if (index != steps.lastIndex) Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

/**
 * The plate-and-cutlery icon, enlarged and faded into the analyzing screen's
 * background as ambient loading motion — the same 🍽️ that sits in the badge at
 * the top, echoed large and soft behind the step list. A slow breathing scale
 * keeps it alive without competing with the text. No image asset, no decoder.
 */
@Composable
private fun AnalyzingBackground(emoji: String) {
    val breathe = rememberInfiniteTransition(label = "bgBreathe")
    val scale by breathe.animateFloat(
        initialValue = 0.94f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bgScale",
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            emoji,
            fontSize = 220.sp,
            modifier = Modifier
                .scale(scale)
                .alpha(0.06f), // faint watermark, keeps the steps fully readable
        )
    }
}

private enum class StepState { Done, Active, Pending }

@Composable
private fun AnalyzingStepRow(text: String, state: StepState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (state) {
                StepState.Done -> Icon(Icons.Filled.Check, null, tint = Ar.Teal, modifier = Modifier.size(18.dp))
                StepState.Active -> CircularProgressIndicator(color = Ar.Teal, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                StepState.Pending -> {}
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = if (state == StepState.Active) FontWeight.SemiBold else FontWeight.Normal,
            color = when (state) {
                StepState.Done -> Ar.Navy
                StepState.Active -> Ar.Navy
                StepState.Pending -> Ar.Muted
            },
        )
    }
}

/** The two-tab switcher at the top of the scan screen — meal vs. ingredients. */
@Composable
private fun ScanTabRow(selected: ScanTab, enabled: Boolean, onSelect: (ScanTab) -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            // Both tab pills stay the same height regardless of whether one
            // label wraps to two lines and the other doesn't.
            .height(IntrinsicSize.Min)
            .background(Ar.CardBg, RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ScanTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (isSelected) Ar.Teal else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(tab) }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(tab.emoji, fontSize = 16.sp)
                Spacer(Modifier.height(3.dp))
                Text(
                    tab.label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) Color.White else Ar.Muted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 13.sp,
                    maxLines = 2,
                )
            }
        }
    }
}

/** Animated corner-bracket viewfinder with a moving scan line — the
 * "camera is really looking" cue while framing a shot. */
@Composable
private fun ScanViewfinderOverlay() {
    val transition = rememberInfiniteTransition(label = "scan")
    val linePos by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "linePos"
    )
    val teal = Color(0xFF3FD1C4)

    Canvas(Modifier.fillMaxSize()) {
        val inset = 26.dp.toPx()
        val frameLeft = inset
        val frameRight = size.width - inset
        val frameTop = size.height * 0.16f
        val frameBottom = size.height * 0.72f
        val cornerLen = 24.dp.toPx()
        val strokeW = 4.dp.toPx()

        fun corner(x: Float, y: Float, dx: Int, dy: Int) {
            drawLine(teal, Offset(x, y), Offset(x + cornerLen * dx, y), strokeW, cap = StrokeCap.Round)
            drawLine(teal, Offset(x, y), Offset(x, y + cornerLen * dy), strokeW, cap = StrokeCap.Round)
        }
        corner(frameLeft, frameTop, 1, 1)
        corner(frameRight, frameTop, -1, 1)
        corner(frameLeft, frameBottom, 1, -1)
        corner(frameRight, frameBottom, -1, -1)

        val lineY = frameTop + (frameBottom - frameTop) * linePos
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(teal.copy(alpha = 0f), teal.copy(alpha = 0.95f), teal.copy(alpha = 0f))
            ),
            start = Offset(frameLeft, lineY),
            end = Offset(frameRight, lineY),
            strokeWidth = 2.dp.toPx()
        )
    }
}

@Composable
private fun ScanStatusBanner(text: String) {
    Row(
        Modifier
            .padding(bottom = 18.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(color = Ar.Teal, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontSize = 12.5.sp)
    }
}

@Composable
private fun BoxScope.ScanErrorBanner(message: String, onRetry: () -> Unit) {
    Column(
        Modifier
            .align(Alignment.Center)
            .padding(horizontal = 24.dp)
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.PriorityHigh, null, tint = Ar.Orange)
        Spacer(Modifier.height(8.dp))
        Text(message, color = Color.White, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .background(Ar.Teal, RoundedCornerShape(999.dp))
                .clickable { onRetry() }
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Refresh, null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Try again", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * ImageCapture's in-memory JPEG, rotated upright and downscaled.
 *
 * A back-camera capture at full sensor resolution (often 8-12MP+) makes the
 * server's vision encoder take far longer than a phone CPU can do in
 * reasonable time — that's what was actually causing the "hung" timeouts,
 * not a broken server. 1024px is plenty for the model to read a fridge.
 */
private fun ImageProxy.toUprightJpegBytes(maxDimension: Int = 1024): ByteArray {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val rotation = imageInfo.rotationDegrees
    val upright = if (rotation != 0) {
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    } else decoded

    val scale = maxDimension.toFloat() / maxOf(upright.width, upright.height)
    val resized = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            upright, (upright.width * scale).toInt(), (upright.height * scale).toInt(), true
        )
    } else upright

    val out = ByteArrayOutputStream()
    resized.compress(Bitmap.CompressFormat.JPEG, 85, out)
    return out.toByteArray()
}

@Composable
fun ScanResultsScreen(
    items: List<ScannedItem>,
    stat: InferenceStat?,
    onBack: () -> Unit,
    onSuggest: (List<ScannedItem>) -> Unit,
) {
    // Local, editable copy — removing a misdetected/unwanted item here doesn't
    // touch the original scan, only what gets carried forward to the meal step.
    val current = remember(items) { items.toMutableStateList() }

    Column(
        Modifier.fillMaxSize().background(Ar.Cream)
            .verticalScroll(rememberScrollState())
    ) {
        ArHeader("Scan results", onBack = onBack)
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("Detected — checked against your diet plan", fontSize = 13.sp, color = Ar.Muted)
            if (stat != null) {
                Spacer(Modifier.height(8.dp))
                InferenceChip(stat)
            }
            Spacer(Modifier.height(10.dp))
            current.forEach { item ->
                ArCard(Modifier.padding(vertical = 5.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ar.Navy)
                            Text(item.quantity, fontSize = 12.5.sp, color = Ar.Slate)
                        }
                        val (fg, bg) = if (item.fitsDiet) Ar.Teal to Ar.TealChipBg else Ar.Orange to Ar.OrangeChipBg
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                Modifier.background(bg, RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (item.fitsDiet) Icons.Filled.Check else Icons.Filled.PriorityHigh,
                                    null, tint = fg, modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (item.fitsDiet) "Fits" else "Check",
                                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove ${item.name}",
                                tint = Ar.Muted,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable { current.remove(item) }
                            )
                        }
                    }
                    if (item.note.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(item.note, fontSize = 12.sp, color = Ar.Muted, lineHeight = 16.sp)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            if (current.isEmpty()) {
                Text(
                    "Nothing left to suggest a recipe from — go back and rescan.",
                    fontSize = 13.sp, color = Ar.Muted,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(12.dp))
            }
            ArPrimaryButton(
                "Suggest a recipe",
                enabled = current.isNotEmpty(),
                onClick = { onSuggest(current.toList()) },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * A concrete, real dish the model proposes from what's ACTUALLY on hand,
 * with its OWN estimated nutrition for that specific dish — never a generic
 * placeholder, never numbers borrowed from somewhere else.
 */
private data class RecipeSuggestion(
    val title: String,
    val description: String,
    val kcal: Int,
    val proteinG: Int,
    val carbsG: Int,
    val fatG: Int,
)

/**
 * Turns whatever's on hand (scanned items, or the pantry if there's nothing
 * scanned) into ONE real dish via [Ml.llm] — live only, same rule as every
 * other ML endpoint: a failure surfaces as a real error, NEVER a fake or
 * generic recipe. Deliberately does not feed in any target macros to aim
 * for — the model estimates nutrition for the specific dish it names, from
 * the actual ingredients, rather than reverse-engineering a dish to match
 * numbers that have nothing to do with it.
 */
private suspend fun suggestRecipe(items: List<ScannedItem>): RecipeSuggestion {
    val context = Store.buildContext()
    val ingredients = items.map { it.name }.ifEmpty { Store.pantry.map { it.name } }
    if (ingredients.isEmpty()) {
        throw IOException("Nothing to suggest a recipe from — scan or add some ingredients first.")
    }

    val prompt =
        "Ingredients actually on hand: ${ingredients.joinToString()}. " +
                "Diet: ${Store.profile.dietTags.joinToString().ifBlank { "unspecified" }}; " +
                "goal: ${Store.profile.goal}." +
                labFlagsClause(context.labFindings) + "\n\n" +
                "Suggest ONE real, simple dish that can actually be made primarily from " +
                "these ingredients — a few common pantry staples (oil, salt, water, basic " +
                "spices) are fine too, but the core of the dish must come from the list " +
                "above. Do not invent ingredients that aren't listed and aren't a common staple.\n\n" +
                "Estimate realistic nutrition for a single serving of THIS specific dish, " +
                "based on its actual ingredients and typical quantities — do not guess round, " +
                "generic numbers.\n\n" +
                "Respond in EXACTLY this format, one field per line, no extra commentary:\n" +
                "NAME: <dish name>\n" +
                "CALORIES: <number> kcal\n" +
                "PROTEIN: <number> g\n" +
                "CARBS: <number> g\n" +
                "FAT: <number> g\n" +
                "DESCRIPTION: <one or two sentences: how it's made, and why it fits their diet/goal>" + structuredLanguageDirective()

    val reply = Ml.llm.respond(listOf(ChatTurn(fromUser = true, text = prompt)), context)
    val fields = reply.lines()
        .mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) null else line.substring(0, idx).trim().uppercase() to line.substring(idx + 1).trim()
        }
        .toMap()

    fun field(key: String) = fields[key].orEmpty()
    fun number(key: String) = field(key).filter { it.isDigit() }.toIntOrNull() ?: 0

    val name = field("NAME")
    if (name.isBlank()) {
        throw IOException(
            "Couldn't come up with a real recipe from that — the model's answer didn't " +
                    "name a dish. Try again or rescan with clearer items."
        )
    }

    return RecipeSuggestion(
        title = name,
        description = field("DESCRIPTION"),
        kcal = number("CALORIES"),
        proteinG = number("PROTEIN"),
        carbsG = number("CARBS"),
        fatG = number("FAT"),
    )
}

@Composable
fun MealSuggestionScreen(
    items: List<ScannedItem>,
    onBack: () -> Unit,
    onWhyThis: () -> Unit,
    onChooseMeal: () -> Unit,
    onScanAgain: () -> Unit,
) {
    // ENGINE's rule-based reasoning (steps/time-of-day) — feeds ONLY the
    // "Why this?" sheet below. It is never shown as if it were this
    // recipe's own name or numbers; those come exclusively from [recipe].
    val rec = remember { RuleEngine.recommend(Store.buildContext()) }
    LaunchedEffect(rec) { Store.lastRecommendation.value = rec }
    val scope = rememberCoroutineScope()

    var recipe by remember { mutableStateOf<RecipeSuggestion?>(null) }
    var recipeStat by remember { mutableStateOf<InferenceStat?>(null) }
    var recipeError by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(items, retryKey) {
        recipe = null
        recipeStat = null
        recipeError = null
        try {
            recipe = suggestRecipe(items)
            recipeStat = InferenceStats.last
        } catch (e: Exception) {
            recipeError = e.message ?: "Couldn't reach the on-device model."
        }
    }

    Column(
        Modifier.fillMaxSize().background(Ar.Cream)
            .verticalScroll(rememberScrollState())
    ) {
        ArHeader("Fridge-to-meal", onBack = onBack)
        Column(Modifier.padding(horizontal = 20.dp)) {
            ArCard {
                when {
                    recipeError != null -> {
                        Text("Couldn't suggest a recipe", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ar.Navy)
                        Spacer(Modifier.height(8.dp))
                        Text(recipeError!!, fontSize = 13.sp, color = Ar.Orange, lineHeight = 18.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Retry", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ar.Teal,
                            modifier = Modifier.clickable { retryKey++ }
                        )
                    }
                    recipe == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Ar.Teal, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Looking at what you scanned and thinking of a real recipe…", fontSize = 13.sp, color = Ar.Muted)
                    }
                    else -> {
                        val r = recipe!!
                        Text(r.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ar.Navy)
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ArChip("${r.kcal} kcal", Ar.Blue, Ar.BlueChipBg)
                            ArChip("${r.proteinG} g protein", Ar.Teal, Ar.TealChipBg)
                            ArChip("${r.carbsG} g carbs", Ar.Amber, Ar.AmberChipBg)
                            ArChip("${r.fatG} g fat", Ar.Orange, Ar.OrangeChipBg)
                        }
                        if (r.description.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(r.description, fontSize = 14.sp, color = Ar.Slate, lineHeight = 20.sp)
                        }
                    }
                }
            }
            if (recipeStat != null) {
                Spacer(Modifier.height(10.dp))
                InferenceChip(recipeStat)
            }
            Spacer(Modifier.height(12.dp))
            ArCard {
                if (items.isNotEmpty()) {
                    Text("From what you scanned", fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold, color = Ar.Muted)
                    Spacer(Modifier.height(8.dp))
                    items.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(item.name, fontSize = 14.sp, color = Ar.Navy)
                            Text(item.quantity, fontSize = 11.sp, color = Ar.Teal)
                        }
                    }
                } else {
                    Text("Uses from your pantry", fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold, color = Ar.Muted)
                    Spacer(Modifier.height(8.dp))
                    rec.usesPantry.ifEmpty { listOf("Pantry not needed for this one") }
                        .forEach { name ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(name, fontSize = 14.sp, color = Ar.Navy)
                                Text("in pantry", fontSize = 11.sp, color = Ar.Teal)
                            }
                        }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Why this?", fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = Ar.Purple,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .background(Ar.PurpleChipBg, RoundedCornerShape(999.dp))
                    .clickable { onWhyThis() }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Spacer(Modifier.height(16.dp))
            val r = recipe
            if (r == null) {
                ArPrimaryButton("Choose this meal", enabled = false, onClick = {})
            } else {
                LogMealButton(
                    mealName = r.title,
                    kcal = r.kcal,
                    proteinG = r.proteinG,
                    carbsG = r.carbsG,
                    fatG = r.fatG,
                    label = "Choose this meal",
                    onLogged = onChooseMeal,
                )
            }
            Spacer(Modifier.height(10.dp))
            ArSecondaryButton("Scan again", onClick = onScanAgain)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Results for the "Scan a Prepared Meal" tab — a live [Ml.mealAnalyzer] call
 * already happened before this screen is shown (see [ScanScreen]); this just
 * displays the macros, fit-vs-plan verdict, and the model's recommendation.
 */
@Composable
fun MealAnalysisResultsScreen(
    analysis: MealAnalysis,
    stat: InferenceStat?,
    onBack: () -> Unit,
    onScanAgain: () -> Unit,
    onLogged: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().background(Ar.Cream)
            .verticalScroll(rememberScrollState())
    ) {
        ArHeader("Meal analysis", onBack = onBack)
        Column(Modifier.padding(horizontal = 20.dp)) {
            ArCard {
                Text(analysis.mealName, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ar.Navy)
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ArChip("${analysis.kcal} kcal", Ar.Blue, Ar.BlueChipBg)
                    ArChip("${analysis.proteinG} g protein", Ar.Teal, Ar.TealChipBg)
                    ArChip("${analysis.carbsG} g carbs", Ar.Amber, Ar.AmberChipBg)
                    ArChip("${analysis.fatG} g fat", Ar.Orange, Ar.OrangeChipBg)
                }
                if (analysis.summary.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(analysis.summary, fontSize = 14.sp, color = Ar.Slate, lineHeight = 20.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            ArCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Does this fit your plan?", fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold, color = Ar.Muted)
                    val (fg, bg) = if (analysis.fitsGoal) Ar.Teal to Ar.TealChipBg else Ar.Orange to Ar.OrangeChipBg
                    Row(
                        Modifier.background(bg, RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (analysis.fitsGoal) Icons.Filled.Check else Icons.Filled.PriorityHigh,
                            null, tint = fg, modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (analysis.fitsGoal) "Fits" else "Check",
                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg
                        )
                    }
                }
                if (analysis.recommendation.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(analysis.recommendation, fontSize = 14.sp, color = Ar.Slate, lineHeight = 20.sp)
                }
            }
            if (stat != null) {
                Spacer(Modifier.height(12.dp))
                InferenceChip(stat)
            }
            Spacer(Modifier.height(20.dp))
            LogMealButton(
                mealName = analysis.mealName,
                kcal = analysis.kcal,
                proteinG = analysis.proteinG,
                carbsG = analysis.carbsG,
                fatG = analysis.fatG,
                onLogged = onLogged,
            )
            Spacer(Modifier.height(10.dp))
            ArSecondaryButton("Scan again", onClick = onScanAgain)
            Spacer(Modifier.height(24.dp))
        }
    }
}
