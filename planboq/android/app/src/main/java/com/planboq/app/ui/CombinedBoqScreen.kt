package com.planboq.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import com.planboq.app.Routes
import com.planboq.app.boq.BoqDocument
import com.planboq.app.boq.BoqItem
import com.planboq.app.boq.BoqRates
import com.planboq.app.boq.BoqSettings
import com.planboq.app.boq.CombinedBoq
import com.planboq.app.boq.QuantityEngine
import com.planboq.app.data.repo.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sums the BOQs of several projects (e.g. Ground + First + Second floors) into
 * one schedule and exports it. Uses each project's saved BOQ, or computes one
 * from its plan and the shared rate library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CombinedBoqScreen(nav: NavController) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val billing by app.billingManager.state.collectAsState()
    val prefs by app.settingsRepository.settings.collectAsState(initial = Settings())
    val entitled = billing.entitled || prefs.debugProOverride
    val projects by app.database.projectDao().observeAll().collectAsState(initial = emptyList())

    var selected by remember { mutableStateOf(setOf<String>()) }
    var items by remember { mutableStateOf<List<BoqItem>>(emptyList()) }
    var settings by remember { mutableStateOf(BoqSettings()) }
    var computing by remember { mutableStateOf(false) }
    var computedCount by remember { mutableStateOf(0) }

    fun compute() {
        if (selected.isEmpty()) return
        scope.launch {
            computing = true
            val library = app.rateRepository.load()
            val entries = mutableListOf<CombinedBoq.Entry>()
            var firstSettings: BoqSettings? = null
            for (id in selected) {
                val doc = app.documentStore.load(id) ?: continue
                val existing = app.boqRepository.load(id)
                val s = existing?.settings ?: firstSettings ?: BoqSettings()
                if (firstSettings == null) firstSettings = s
                val itemList = existing?.items?.takeIf { it.isNotEmpty() }
                    ?: BoqRates.apply(QuantityEngine.compute(doc, s).items, library)
                entries += CombinedBoq.Entry(doc.name, itemList)
            }
            settings = firstSettings ?: BoqSettings()
            items = CombinedBoq.combine(entries)
            computedCount = entries.size
            computing = false
        }
    }

    fun export(kind: CombinedExport) {
        if (items.isEmpty()) return
        if (!entitled) {
            scope.launch { snackbar.showSnackbar("Exports are a Pro feature.") }
            nav.navigate(Routes.PAYWALL)
            return
        }
        scope.launch {
            try {
                val doc = BoqDocument(projectId = "combined", settings = settings, items = items)
                val ext = if (kind == CombinedExport.XLSX) "xlsx" else "pdf"
                val file = File(app.documentStore.exportsDir(), "combined_BOQ.$ext")
                withContext(Dispatchers.IO) {
                    if (kind == CombinedExport.XLSX) {
                        app.boqExporter.exportXlsx(doc, null, "Combined BOQ ($computedCount projects)", file)
                    } else {
                        app.boqExporter.exportPdf(doc, null, "Combined BOQ ($computedCount projects)", file)
                    }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = if (kind == CombinedExport.XLSX) {
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    } else "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Share combined BOQ"))
            } catch (t: Throwable) {
                snackbar.showSnackbar("Export failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Combined BOQ") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        ) {
            item {
                Text(
                    "Select the projects (floors) to combine. Quantities are summed per item code; rates come from each project or the rate library.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(projects, key = { it.id }) { project ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = selected.contains(project.id),
                        onCheckedChange = { checked ->
                            selected = if (checked) selected + project.id else selected - project.id
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(project.name)
                        Text(
                            project.sourceType,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = { compute() },
                    enabled = selected.isNotEmpty() && !computing,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (computing) "Computing…" else "Compute combined BOQ") }
            }
            if (computing) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            items(items, key = { it.code + it.id }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.description, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${item.code} · ${"%.3f".format(item.quantity)} ${item.unit}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("%.2f".format(item.amount()), style = MaterialTheme.typography.bodyMedium)
                            Text("rate %.2f".format(item.rate), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (items.isNotEmpty()) {
                item {
                    val total = items.sumOf { it.amount() }
                    val contingency = total * settings.contingencyPercent / 100.0
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Subtotal", style = MaterialTheme.typography.bodyLarge)
                                Text("${settings.currency} %.2f".format(total))
                            }
                            if (settings.contingencyPercent != 0.0) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Contingency ${settings.contingencyPercent}%")
                                    Text("${settings.currency} %.2f".format(contingency))
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Grand total", style = MaterialTheme.typography.titleMedium)
                                Text("${settings.currency} %.2f".format(total + contingency), style = MaterialTheme.typography.titleMedium)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { export(CombinedExport.XLSX) }) { Text("Export Excel") }
                                Button(onClick = { export(CombinedExport.PDF) }) { Text("Export PDF") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class CombinedExport { XLSX, PDF }
