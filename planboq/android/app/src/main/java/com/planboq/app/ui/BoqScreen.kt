package com.planboq.app.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PriceChange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.planboq.app.boq.BoqMerge
import com.planboq.app.boq.BoqRates
import com.planboq.app.boq.BoqSettings
import com.planboq.app.boq.OpeningType
import com.planboq.app.boq.BoqReadiness
import com.planboq.app.boq.QuantityEngine
import com.planboq.app.boq.QuantityResult
import com.planboq.app.boq.QuantitySource
import com.planboq.app.boq.RateLibrary
import com.planboq.app.boq.Readiness
import com.planboq.app.data.geometry.LayerKind
import com.planboq.app.data.geometry.PlanDocument
import com.planboq.app.data.repo.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private enum class EditField { QUANTITY, RATE }

private data class EditTarget(val itemId: String, val field: EditField)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoqScreen(nav: NavController, projectId: String) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val billing by app.billingManager.state.collectAsState()
    val prefs by app.settingsRepository.settings.collectAsState(initial = Settings())
    val entitled = billing.entitled || prefs.debugProOverride

    var plan by remember { mutableStateOf<PlanDocument?>(null) }
    var boq by remember { mutableStateOf<BoqDocument?>(null) }
    var library by remember { mutableStateOf(RateLibrary()) }
    var warnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var readiness by remember { mutableStateOf<Readiness?>(null) }
    var loading by remember { mutableStateOf(true) }
    var editTarget by remember { mutableStateOf<EditTarget?>(null) }
    var showAddItem by remember { mutableStateOf(false) }
    var addDescription by remember { mutableStateOf("") }
    var addUnit by remember { mutableStateOf("nos") }
    var addQty by remember { mutableStateOf("") }
    var addRate by remember { mutableStateOf("") }
    val expanded = remember { mutableStateListOf<String>() }

    fun persist(updated: BoqDocument) {
        boq = updated
        readiness = BoqReadiness.evaluate(plan, updated.settings, updated.items, updated.acceptedGaps)
        scope.launch { app.boqRepository.save(updated) }
    }

    fun recompute(settings: BoqSettings? = null) {
        val doc = plan ?: return
        val base = boq ?: BoqDocument(projectId)
        val s = settings ?: base.settings
        val result: QuantityResult = QuantityEngine.compute(doc, s)
        warnings = result.warnings
        val rated = BoqRates.apply(result.items, library)
        persist(
            base.copy(
                settings = s,
                items = BoqMerge.merge(base.items, rated),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    LaunchedEffect(projectId) {
        var doc = app.documentStore.load(projectId)
        if (doc != null && doc.rooms.isEmpty() && doc.scale.calibrated) {
            val rooms = withContext(Dispatchers.Default) {
                app.imageProcessor.ensureOpenCv()
                app.roomDetector.detect(doc)
            }
            if (rooms.isNotEmpty()) {
                doc = doc.copyWithTouch(rooms = rooms)
                app.documentStore.save(doc)
            }
        }
        plan = doc

        library = app.rateRepository.load()
        val existing = app.boqRepository.load(projectId)
        val base = existing ?: BoqDocument(projectId)
        if (doc == null) {
            warnings = listOf("Plan document not found")
            loading = false
            return@LaunchedEffect
        }
        val result = QuantityEngine.compute(doc, base.settings)
        warnings = result.warnings
        val rated = BoqRates.apply(result.items, library)
        persist(base.copy(items = BoqMerge.merge(base.items, rated)))
        loading = false
    }

    fun updateItem(itemId: String, transform: (BoqItem) -> BoqItem) {
        val b = boq ?: return
        persist(
            b.copy(
                items = b.items.map { if (it.id == itemId) transform(it) else it },
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    fun export(kind: ExportKind) {
        val d = boq ?: return
        val p = plan ?: return
        if (!entitled) {
            scope.launch { snackbar.showSnackbar("BOQ export is a Pro feature.") }
            nav.navigate(Routes.PAYWALL)
            return
        }
        val r = readiness
        if (r != null && !r.isExportable) {
            scope.launch {
                snackbar.showSnackbar("Resolve the blocking gaps before exporting.")
            }
            nav.navigate(Routes.setup(projectId))
            return
        }
        scope.launch {
            try {
                val ext = if (kind == ExportKind.XLSX) "xlsx" else "pdf"
                val baseName = p.name.let { n ->
                    val dot = n.lastIndexOf('.')
                    if (dot > 0 && n.length - dot <= 5) n.substring(0, dot) else n
                }
                val safe = baseName.replace(Regex("[^A-Za-z0-9 _.-]"), "_").take(40).ifBlank { "plan" }
                val file = File(app.documentStore.exportsDir(), "${safe}_BOQ.$ext")
                withContext(Dispatchers.IO) {
                    if (kind == ExportKind.XLSX) {
                        app.boqExporter.exportXlsx(d, p, p.name, file, r)
                    } else {
                        app.boqExporter.exportPdf(d, p, p.name, file, r)
                    }
                }
                shareBoqFile(
                    context, file,
                    if (kind == ExportKind.XLSX) "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" else "application/pdf",
                )
            } catch (t: Throwable) {
                snackbar.showSnackbar("Export failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Bill of Quantities", style = MaterialTheme.typography.titleMedium)
                        Text(plan?.name ?: "", style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.RATES) }) {
                        Icon(Icons.Default.PriceChange, contentDescription = "Rate library")
                    }
                    IconButton(onClick = { recompute() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Recalculate")
                    }
                    IconButton(onClick = { nav.navigate(Routes.setup(projectId)) }) {
                        Icon(Icons.Default.Tune, contentDescription = "Complete BOQ data")
                    }
                },
            )
        },
        floatingActionButton = {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = {
                    addDescription = ""
                    addUnit = "nos"
                    addQty = ""
                    addRate = ""
                    showAddItem = true
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add item") },
            )
        },
    ) { padding ->
        if (loading) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        val document = boq
        val grouped: Map<String, List<BoqItem>> = document?.items?.groupBy { it.category } ?: emptyMap()

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            readiness?.let { r ->
                item(key = "readiness") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("BOQ readiness", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${r.score}%",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (r.isExportable) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                                )
                            }
                            Text(
                                "${r.blocking} blocking, ${r.important} assumptions, ${r.optional} optional gaps",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (!r.isExportable) {
                                Text(
                                    "Export is blocked until the blocking gaps are resolved.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Button(onClick = { nav.navigate(Routes.setup(projectId)) }) {
                                Text("Complete data")
                            }
                        }
                    }
                }
            }

            if (warnings.isNotEmpty()) {
                item(key = "warnings") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Needs attention", style = MaterialTheme.typography.titleSmall)
                            warnings.forEach {
                                Text("• $it", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            grouped.forEach { (category, list) ->
                item(key = "cat_$category") {
                    Text(
                        category,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(list, key = { it.id }) { item ->
                    BoqItemCard(
                        item = item,
                        expanded = expanded.contains(item.id),
                        onToggleDetail = {
                            if (expanded.contains(item.id)) expanded.remove(item.id) else expanded.add(item.id)
                        },
                        onEditQty = { editTarget = EditTarget(item.id, EditField.QUANTITY) },
                        onEditRate = { editTarget = EditTarget(item.id, EditField.RATE) },
                        onDelete = {
                            val b = boq ?: return@BoqItemCard
                            persist(
                                b.copy(
                                    items = b.items.filterNot { it.id == item.id },
                                    updatedAt = System.currentTimeMillis(),
                                ),
                            )
                        },
                    )
                }
                item(key = "sub_$category") {
                    val amount = document?.categoryTotals()?.get(category) ?: 0.0
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Subtotal - $category",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "%.2f".format(amount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item(key = "total") {
                val subtotal = document?.total() ?: 0.0
                val contingency = document?.contingencyAmount() ?: 0.0
                val currency = document?.settings?.currency ?: "$"
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Subtotal", style = MaterialTheme.typography.bodyLarge)
                            Text("$currency %.2f".format(subtotal), style = MaterialTheme.typography.bodyLarge)
                        }
                        if ((document?.settings?.contingencyPercent ?: 0.0) != 0.0) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    "Contingency ${document?.settings?.contingencyPercent}%",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text("$currency %.2f".format(contingency), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Grand total", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "$currency %.2f".format(document?.grandTotal() ?: 0.0),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Text(
                            if (entitled) "Pro - clean exports" else "Free plan - BOQ export requires Pro",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { export(ExportKind.XLSX) }) { Text("Export Excel") }
                            Button(onClick = { export(ExportKind.PDF) }) { Text("Export PDF") }
                        }
                    }
                }
            }
        }
    }

    // ---- dialogs / sheets ----
    editTarget?.let { target ->
        val item = boq?.items?.firstOrNull { it.id == target.itemId }
        if (item != null) {
            val isQty = target.field == EditField.QUANTITY
            var text by remember(target) {
                mutableStateOf(if (isQty) item.quantity.toString() else item.rate.toString())
            }
            AlertDialog(
                onDismissRequest = { editTarget = null },
                title = { Text(if (isQty) "Quantity" else "Rate") },
                text = {
                    Column {
                        Text(item.description, style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = { Text(if (isQty) "Quantity (${item.unit})" else "Rate") },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val value = text.replace(",", ".").toDoubleOrNull()
                        if (value != null && value >= 0) {
                            updateItem(target.itemId) { current ->
                                if (isQty) {
                                    current.copy(quantity = value, source = QuantitySource.MANUAL)
                                } else {
                                    current.copy(rate = value)
                                }
                            }
                        }
                        editTarget = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { editTarget = null }) { Text("Cancel") } },
            )
        }
    }

    if (showAddItem) {
        AlertDialog(
            onDismissRequest = { showAddItem = false },
            title = { Text("Add BOQ item") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(addDescription, { addDescription = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(addUnit, { addUnit = it }, label = { Text("Unit") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(addQty, { addQty = it }, label = { Text("Quantity") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(addRate, { addRate = it }, label = { Text("Rate") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val b = boq
                        val description = addDescription.trim()
                        if (b != null && description.isNotEmpty()) {
                            val item = BoqItem(
                                code = "C${b.items.count { it.code.startsWith("C") } + 1}",
                                description = description,
                                unit = addUnit.trim().ifBlank { "nos" },
                                quantity = addQty.replace(",", ".").toDoubleOrNull() ?: 0.0,
                                rate = addRate.replace(",", ".").toDoubleOrNull() ?: 0.0,
                                category = "Custom",
                                source = QuantitySource.MANUAL,
                            )
                            persist(
                                b.copy(
                                    items = b.items + item,
                                    updatedAt = System.currentTimeMillis(),
                                ),
                            )
                        }
                        showAddItem = false
                    },
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddItem = false }) { Text("Cancel") } },
        )
    }

}

private enum class ExportKind { XLSX, PDF }

@Composable
private fun BoqItemCard(
    item: BoqItem,
    expanded: Boolean,
    onToggleDetail: () -> Unit,
    onEditQty: () -> Unit,
    onEditRate: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(item.description, style = MaterialTheme.typography.bodyMedium)
                    if (item.note.isNotBlank()) {
                        Text(
                            item.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete item")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = { },
                    label = {
                        Text(
                            when (item.source) {
                                QuantitySource.MEASURED -> "Measured"
                                QuantitySource.ASSUMED -> "Assumed"
                                QuantitySource.MANUAL -> "Manual"
                            },
                        )
                    },
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "%.3f %s".format(item.quantity, item.unit),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.clickable { onEditQty() },
                    )
                    Text(
                        "tap to edit quantity",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "%.2f".format(item.rate),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.clickable { onEditRate() },
                    )
                    Text(
                        "rate",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Text("%.2f".format(item.amount()), style = MaterialTheme.typography.bodyLarge)
            }
            if (item.detail.isNotBlank()) {
                Text(
                    if (expanded) "How calculated ▾" else "How calculated ▸",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onToggleDetail() },
                )
                if (expanded) {
                    Text(
                        item.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun shareBoqFile(context: android.content.Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share BOQ"))
}
