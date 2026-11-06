package com.planboq.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Polyline
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.planboq.app.Routes
import com.planboq.app.data.billing.FreeTier
import com.planboq.app.data.dxf.DxfParser
import com.planboq.app.data.geometry.PlanDocument
import com.planboq.app.data.pdf.VectorPdfExtractor
import com.planboq.app.data.repo.ProjectEntity
import com.planboq.app.data.repo.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(nav: NavController) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by app.settingsRepository.settings.collectAsState(initial = Settings())
    val billingState by app.billingManager.state.collectAsState()

    val entitled = billingState.entitled || settings.debugProOverride
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Free-tier gate: a conversion is only recorded once the import succeeds.
    fun gate(onAllowed: () -> Unit) {
        if (!FreeTier.canConvert(entitled, settings.conversionsThisMonth)) {
            error = "Free plan limit reached (${FreeTier.MONTHLY_LIMIT} conversions/month). Upgrade for unlimited conversions."
            return
        }
        busy = true
        onAllowed()
    }

    fun finishImport(doc: PlanDocument) {
        scope.launch {
            app.settingsRepository.recordConversion()
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
            nav.navigate(Routes.editor(doc.id)) { popUpTo(Routes.PROJECTS) }
        }
    }

    val pickDxf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) gate {
            scope.launch {
                try {
                    val (bytes, name) = withContext(Dispatchers.IO) { readUri(context, uri) }
                    val isPdf = bytes.size > 4 &&
                        bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() &&
                        bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte()
                    val doc = withContext(Dispatchers.Default) {
                        if (isPdf) {
                            // user picked a PDF from the DXF card - route it to the PDF reader
                            VectorPdfExtractor().extractFirstPage(bytes, name)
                        } else {
                            DxfParser().parse(bytes.inputStream().reader(), fallbackMmPerUnit = 1.0, fileName = name)
                        }
                    }
                    if (doc.segments.isEmpty() && doc.polylines.isEmpty()) {
                        busy = false
                        error = if (isPdf) {
                            "No vector geometry found on page 1 of this PDF (text or raster only?)."
                        } else {
                            "No DXF geometry found. This may not be a DXF file - if it is a PDF, use \"Vector PDF\"."
                        }
                    } else {
                        finishImport(doc)
                    }
                } catch (t: Throwable) {
                    busy = false
                    error = "Could not read DXF: ${t.message ?: t.javaClass.simpleName}"
                }
            }
        }
    }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) gate {
            scope.launch {
                try {
                    val (bytes, name) = withContext(Dispatchers.IO) { readUri(context, uri) }
                    val doc = withContext(Dispatchers.Default) {
                        VectorPdfExtractor().extractFirstPage(bytes, name)
                    }
                    if (doc.segments.isEmpty() && doc.polylines.isEmpty()) {
                        busy = false
                        error = "No vector geometry found on page 1 of this PDF (text or raster only?)."
                    } else {
                        finishImport(doc)
                    }
                } catch (t: Throwable) {
                    busy = false
                    error = "Could not read PDF: ${t.message ?: t.javaClass.simpleName}"
                }
            }
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) gate {
            scope.launch {
                try {
                    val bmp = withContext(Dispatchers.IO) { loadDownscaled(context, uri, 2400) }
                    if (bmp == null) {
                        busy = false
                        error = "Could not read the photo"
                    } else {
                        app.pendingPhotoBitmap = bmp
                        busy = false
                        nav.navigate(Routes.CORNER_TAP)
                    }
                } catch (t: Throwable) {
                    busy = false
                    error = "Could not read the photo: ${t.message}"
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New plan") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) Text("Working…", style = MaterialTheme.typography.bodyLarge)

            Text(
                if (entitled) {
                    "Pro: unlimited conversions"
                } else {
                    "Free plan: ${FreeTier.remaining(false, settings.conversionsThisMonth)} of ${FreeTier.MONTHLY_LIMIT} conversions left this month"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ImportCard(
                icon = { Icon(Icons.Outlined.Polyline, contentDescription = null) },
                title = "DXF drawing",
                subtitle = "Most accurate. Real lines, layers and units.",
                enabled = !busy,
            ) { pickDxf.launch(arrayOf("*/*")) }

            ImportCard(
                icon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                title = "Vector PDF",
                subtitle = "Reads line geometry from page 1.",
                enabled = !busy,
            ) { pickPdf.launch(arrayOf("application/pdf", "*/*")) }

            ImportCard(
                icon = { Icon(Icons.Outlined.CameraAlt, contentDescription = null) },
                title = "Photograph a plan",
                subtitle = "Tap the 4 corners; lines are traced from the image.",
                enabled = !busy,
            ) { nav.navigate(Routes.CAMERA) }

            ImportCard(
                icon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                title = "Choose a photo or scan",
                subtitle = "Same tracing pipeline, from your gallery.",
                enabled = !busy,
            ) {
                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }

            Text(
                "Traced images are estimates: always verify dimensions against a known measurement before use.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportCard(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private suspend fun readUri(context: android.content.Context, uri: Uri): Pair<ByteArray, String> {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalStateException("Cannot open file")
    val name = queryDisplayName(context, uri) ?: "plan"
    return bytes to name
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

internal fun loadDownscaled(context: android.content.Context, uri: Uri, maxDim: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
} catch (t: Throwable) {
    null
}
