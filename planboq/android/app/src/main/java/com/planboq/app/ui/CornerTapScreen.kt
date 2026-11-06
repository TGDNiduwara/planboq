package com.planboq.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.planboq.app.Routes
import com.planboq.app.data.geometry.Vec2
import com.planboq.app.data.repo.ProjectEntity
import com.planboq.app.data.repo.Settings
import com.planboq.app.domain.ImageToPlanUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/**
 * CamScanner-style confirmation: the page quad is detected automatically and
 * pre-placed; the user only nudges a corner if the detection was off, then
 * taps Trace. All corners are kept in BITMAP coordinates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CornerTapScreen(nav: NavController) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by app.settingsRepository.settings.collectAsState(initial = Settings())

    val bitmap = app.pendingPhotoBitmap
    var corners by remember(bitmap) { mutableStateOf(defaultCorners(bitmap)) }
    var detectionNote by remember { mutableStateOf<String?>(null) }
    var autoDetecting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var dragIndex by remember { mutableStateOf(-1) }

    fun runDetection() {
        val b = bitmap ?: return
        scope.launch {
            autoDetecting = true
            val detection = withContext(Dispatchers.Default) {
                if (app.imageProcessor.ensureOpenCv()) app.pageDetector.detect(b) else null
            }
            if (detection != null) {
                corners = detection.corners
                detectionNote = if (detection.usedFallback) {
                    "Page found approximately - adjust the corners if needed."
                } else {
                    "Page detected - adjust the corners if needed."
                }
            } else {
                detectionNote = "Could not auto-detect the page - drag the corners onto the sheet."
            }
            autoDetecting = false
        }
    }

    LaunchedEffect(bitmap) { runDetection() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Confirm page") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (bitmap == null) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No photo selected.")
                Button(onClick = { nav.popBackStack() }) { Text("Back") }
            }
            return@Scaffold
        }

        val imgW = bitmap.width.toFloat()
        val imgH = bitmap.height.toFloat()
        val fitScale = if (containerSize.width == 0 || containerSize.height == 0) {
            1f
        } else {
            minOf(containerSize.width / imgW, containerSize.height / imgH)
        }
        val fitDx = (containerSize.width - imgW * fitScale) / 2f
        val fitDy = (containerSize.height - imgH * fitScale) / 2f

        fun toView(p: Vec2): Offset = Offset(p.x.toFloat() * fitScale + fitDx, p.y.toFloat() * fitScale + fitDy)

        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .onSizeChanged { containerSize = it },
        ) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Plan photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )

            val accent = MaterialTheme.colorScheme.tertiary

            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(bitmap) {
                        // live reads: containerSize/corners are state-backed, so
                        // recomputing here avoids stale captures
                        fun fit(): Triple<Float, Float, Float> {
                            val cs = containerSize
                            if (cs.width == 0 || cs.height == 0) return Triple(1f, 0f, 0f)
                            val s = minOf(cs.width / imgW, cs.height / imgH)
                            return Triple(s, (cs.width - imgW * s) / 2f, (cs.height - imgH * s) / 2f)
                        }
                        fun toViewLive(p: Vec2): Offset {
                            val (s, dx, dy) = fit()
                            return Offset(p.x.toFloat() * s + dx, p.y.toFloat() * s + dy)
                        }
                        detectDragGestures(
                            onDragStart = { pos ->
                                dragIndex = corners.indices.minByOrNull {
                                    val v = toViewLive(corners[it])
                                    hypot(pos.x - v.x, pos.y - v.y)
                                }?.takeIf { idx ->
                                    val v = toViewLive(corners[idx])
                                    hypot(pos.x - v.x, pos.y - v.y) < 80f
                                } ?: -1
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                if (dragIndex >= 0) {
                                    val (s, _, _) = fit()
                                    val moved = corners.toMutableList()
                                    val c = moved[dragIndex]
                                    moved[dragIndex] = Vec2(
                                        (c.x + amount.x / s).coerceIn(0.0, imgW.toDouble()),
                                        (c.y + amount.y / s).coerceIn(0.0, imgH.toDouble()),
                                    )
                                    corners = moved
                                }
                            },
                            onDragEnd = { dragIndex = -1 },
                        )
                    },
            ) {
                for (i in corners.indices) {
                    val a = toView(corners[i])
                    val b = toView(corners[(i + 1) % corners.size])
                    drawLine(accent, a, b, strokeWidth = 3f)
                }
                corners.forEach { c ->
                    val v = toView(c)
                    drawCircle(Color.White, radius = 14f, center = v)
                    drawCircle(accent, radius = 14f, center = v, style = Stroke(width = 4f))
                }
            }

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                detectionNote?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                if (autoDetecting) {
                    Text("Detecting page…", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Drag a handle only if the box misses the sheet corners.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { runDetection() },
                        enabled = !autoDetecting && !busy,
                    ) { Text("Auto-detect") }
                    Button(
                        onClick = {
                            if (busy) return@Button
                            busy = true
                            error = null
                            scope.launch {
                                val outcome = withContext(Dispatchers.Default) {
                                    app.imageToPlan.run(
                                        bitmap = bitmap,
                                        corners = corners,
                                        fileName = "Traced plan",
                                        serverUrl = settings.serverUrl.ifBlank { null },
                                        apiKey = settings.apiKey.ifBlank { null },
                                    )
                                }
                                when (outcome) {
                                    is ImageToPlanUseCase.Outcome.Success -> {
                                        app.settingsRepository.recordConversion()
                                        val bgPath = app.documentStore.saveBackground(
                                            outcome.document.id, outcome.correctedBitmap,
                                        )
                                        val doc = outcome.document.copy(backgroundImagePath = bgPath)
                                        app.documentStore.save(doc)
                                        app.database.projectDao().upsert(
                                            ProjectEntity(
                                                id = doc.id,
                                                name = doc.name,
                                                sourceType = doc.sourceType.name,
                                                createdAt = doc.createdAt,
                                                updatedAt = doc.updatedAt,
                                            ),
                                        )
                                        busy = false
                                        val msg = when {
                                            outcome.viaServer -> "Traced on server"
                                            outcome.serverError != null -> "Server unavailable - traced on device"
                                            else -> "Traced on device"
                                        }
                                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                        nav.navigate(Routes.editor(doc.id)) { popUpTo(Routes.PROJECTS) }
                                    }
                                    is ImageToPlanUseCase.Outcome.Failed -> {
                                        busy = false
                                        error = outcome.message
                                    }
                                }
                            }
                        },
                        enabled = !busy && !autoDetecting,
                    ) { Text(if (busy) "Tracing…" else "Trace plan") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private fun defaultCorners(bitmap: Bitmap?): List<Vec2> {
    if (bitmap == null) return emptyList()
    val w = bitmap.width.toDouble()
    val h = bitmap.height.toDouble()
    return listOf(
        Vec2(w * 0.08, h * 0.08),
        Vec2(w * 0.92, h * 0.08),
        Vec2(w * 0.92, h * 0.92),
        Vec2(w * 0.08, h * 0.92),
    )
}
