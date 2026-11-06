package com.planboq.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import com.planboq.app.Routes
import com.planboq.app.data.billing.FreeTier
import com.planboq.app.domain.ExportPlanUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(nav: NavController, projectId: String) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val doc by produceState<com.planboq.app.data.geometry.PlanDocument?>(null, projectId) {
        value = app.documentStore.load(projectId)
    }
    val billingState by app.billingManager.state.collectAsState()
    val settings by app.settingsRepository.settings.collectAsState(initial = com.planboq.app.data.repo.Settings())

    val entitled = billingState.entitled || settings.debugProOverride
    var format by remember { mutableStateOf(ExportPlanUseCase.Format.PDF) }
    var exporting by remember { mutableStateOf(false) }
    var resultFile by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val watermark = FreeTier.watermarkNeeded(entitled)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            doc?.let { d ->
                Text(d.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Scale: ${d.scale.label}" + if (!d.scale.calibrated) " - calibrate before exporting!" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (d.scale.calibrated) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = format == ExportPlanUseCase.Format.PDF,
                    onClick = { format = ExportPlanUseCase.Format.PDF },
                    label = { Text("Vector PDF") },
                )
                FilterChip(
                    selected = format == ExportPlanUseCase.Format.DXF,
                    onClick = { format = ExportPlanUseCase.Format.DXF },
                    label = { Text("DXF") },
                )
            }

            if (watermark) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Free plan", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Exports carry a watermark. Upgrade to Pro for clean exports and unlimited conversions.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Text(
                if (doc?.tracedFromImage == true) {
                    "Traced exports include the label: \"Traced from image. Verify before construction.\""
                } else {
                    "Every export includes a verify-before-use note and a scale bar."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = {
                    val d = doc ?: return@Button
                    exporting = true
                    error = null
                    scope.launch {
                        try {
                            val res = withContext(Dispatchers.IO) {
                                app.exporter.export(d, format, watermark)
                            }
                            resultFile = res.file
                            shareFile(context, res.file, format.mime)
                        } catch (t: Throwable) {
                            error = "Export failed: ${t.message ?: t.javaClass.simpleName}"
                        }
                        exporting = false
                    }
                },
                enabled = doc != null && !exporting,
            ) { Text(if (exporting) "Exporting…" else "Export ${format.name}") }

            resultFile?.let {
                Text("Saved: ${it.name}", style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun shareFile(context: android.content.Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share plan"))
}
