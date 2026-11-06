package com.planboq.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.planboq.app.boq.RateEntry
import com.planboq.app.boq.RateLibrary
import kotlinx.coroutines.launch

/**
 * Global rate library: material + labour + overhead build-ups (or a direct
 * rate). BOQ items without a project rate pick these up by code.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RateLibraryScreen(nav: NavController) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()

    var library by remember { mutableStateOf(RateLibrary()) }
    var editing by remember { mutableStateOf<RateEntry?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { library = app.rateRepository.load() }

    fun save(entries: List<RateEntry>) {
        val updated = RateLibrary(entries)
        library = updated
        scope.launch { app.rateRepository.save(updated) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rate library") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { editing = null; showEditor = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add rate")
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
                    "Rates are shared across all projects. A BOQ item uses the rate with the same code unless you override it in the project.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (library.entries.isEmpty()) {
                item { Text("No rates yet. Tap + to add one.") }
            }
            items(library.entries, key = { it.id }) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${entry.code} - ${entry.description}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (entry.directRate != null) {
                                    "Direct rate ${entry.effectiveRate()}"
                                } else {
                                    "Material ${entry.materialCost} + labour ${entry.labourCost} " +
                                        "+ ${entry.overheadPercent}% = ${entry.effectiveRate()}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(entry.unit, style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { editing = entry; showEditor = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Edit rate")
                        }
                        IconButton(onClick = { save(library.entries.filterNot { it.id == entry.id }) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete rate")
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        RateEditorDialog(
            initial = editing,
            onDismiss = { showEditor = false },
            onSave = { entry ->
                val entries = if (library.entries.any { it.id == entry.id }) {
                    library.entries.map { if (it.id == entry.id) entry else it }
                } else {
                    library.entries + entry
                }
                save(entries)
                showEditor = false
            },
        )
    }
}

@Composable
private fun RateEditorDialog(
    initial: RateEntry?,
    onDismiss: () -> Unit,
    onSave: (RateEntry) -> Unit,
) {
    var code by remember { mutableStateOf(initial?.code ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var unit by remember { mutableStateOf(initial?.unit ?: "m3") }
    var material by remember { mutableStateOf(initial?.materialCost?.toString() ?: "0") }
    var labour by remember { mutableStateOf(initial?.labourCost?.toString() ?: "0") }
    var overhead by remember { mutableStateOf(initial?.overheadPercent?.toString() ?: "0") }
    var direct by remember { mutableStateOf(initial?.directRate?.toString() ?: "") }

    fun num(text: String): Double? = text.replace(",", ".").toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add rate" else "Edit rate") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(code, { code = it }, label = { Text("Item code (e.g. W01)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(unit, { unit = it }, label = { Text("Unit") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(material, { material = it }, label = { Text("Material cost per unit") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(labour, { labour = it }, label = { Text("Labour cost per unit") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(overhead, { overhead = it }, label = { Text("Overhead %") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(direct, { direct = it }, label = { Text("Direct rate (blank = use build-up)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                val preview = RateEntry(
                    code = code, description = description, unit = unit,
                    materialCost = num(material) ?: 0.0,
                    labourCost = num(labour) ?: 0.0,
                    overheadPercent = num(overhead) ?: 0.0,
                    directRate = if (direct.isBlank()) null else num(direct),
                ).effectiveRate()
                Text("Effective rate: %.2f".format(preview), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val entry = RateEntry(
                        id = initial?.id ?: java.util.UUID.randomUUID().toString(),
                        code = code.trim(),
                        description = description.trim(),
                        unit = unit.trim().ifBlank { "nos" },
                        materialCost = num(material) ?: 0.0,
                        labourCost = num(labour) ?: 0.0,
                        overheadPercent = num(overhead) ?: 0.0,
                        directRate = if (direct.isBlank()) null else num(direct),
                    )
                    if (entry.code.isNotBlank()) onSave(entry)
                },
                enabled = code.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
