package com.planboq.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.planboq.app.Routes
import com.planboq.app.boq.BoqDocument
import com.planboq.app.boq.BoqMerge
import com.planboq.app.boq.BoqRates
import com.planboq.app.boq.BoqReadiness
import com.planboq.app.boq.BoqSettings
import com.planboq.app.boq.FoundationType
import com.planboq.app.boq.MeasurementStandard
import com.planboq.app.boq.OpeningType
import com.planboq.app.boq.QuantityEngine
import com.planboq.app.boq.Readiness
import com.planboq.app.boq.Standards
import com.planboq.app.boq.StructuralData
import com.planboq.app.data.geometry.LayerKind
import com.planboq.app.data.geometry.PlanDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private data class OpeningRow(
    val id: String,
    val kind: LayerKind,
    val name: String,
    val width: String,
    val height: String,
)

/**
 * Guided data collection for an "almost ready" BOQ: resolves blockers
 * (scale, rooms, walls), confirms assumptions, collects structural data and
 * project information, and records accepted gaps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoqSetupScreen(nav: NavController, projectId: String) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var plan by remember { mutableStateOf<PlanDocument?>(null) }
    var boq by remember { mutableStateOf<BoqDocument?>(null) }
    var readiness by remember { mutableStateOf<Readiness?>(null) }
    var loading by remember { mutableStateOf(true) }
    var detectingRooms by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf<Set<String>>(emptySet()) }

    // form state
    var standard by remember { mutableStateOf(MeasurementStandard.GENERIC_METRIC) }
    var currency by remember { mutableStateOf("$") }
    var storeyHeight by remember { mutableStateOf("3.0") }
    var slabThickness by remember { mutableStateOf("0.125") }
    var storeys by remember { mutableStateOf("1") }
    var extThickness by remember { mutableStateOf("230") }
    var intThickness by remember { mutableStateOf("115") }
    var wastage by remember { mutableStateOf("5") }
    var contingency by remember { mutableStateOf("0") }
    var structuralEnabled by remember { mutableStateOf(false) }
    var foundation by remember { mutableStateOf(FoundationType.ISOLATED) }
    var columnCount by remember { mutableStateOf("0") }
    var columnWidth by remember { mutableStateOf("300") }
    var columnDepth by remember { mutableStateOf("300") }
    var footingLength by remember { mutableStateOf("1.2") }
    var footingWidth by remember { mutableStateOf("1.2") }
    var footingDepth by remember { mutableStateOf("0.3") }
    var beamWidth by remember { mutableStateOf("230") }
    var beamDepth by remember { mutableStateOf("450") }
    var beamLength by remember { mutableStateOf("0") }
    var steelPerM3 by remember { mutableStateOf("90") }
    var client by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var floorName by remember { mutableStateOf("") }
    var preparedBy by remember { mutableStateOf("") }
    var checkedBy by remember { mutableStateOf("") }
    var revision by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    val openingRows = remember { mutableStateListOf<OpeningRow>() }

    fun seed(settings: BoqSettings, doc: PlanDocument?) {
        standard = settings.standard
        currency = settings.currency
        storeyHeight = settings.storeyHeightM.toString()
        slabThickness = settings.slabThicknessM.toString()
        storeys = settings.storeys.toString()
        extThickness = settings.externalWallThicknessMm.toString()
        intThickness = settings.internalWallThicknessMm.toString()
        wastage = settings.wastagePercent.toString()
        contingency = settings.contingencyPercent.toString()
        val st = settings.structural
        structuralEnabled = st.enabled
        foundation = st.foundationType
        columnCount = st.columnCount.toString()
        columnWidth = st.columnWidthMm.toString()
        columnDepth = st.columnDepthMm.toString()
        footingLength = st.footingLengthM.toString()
        footingWidth = st.footingWidthM.toString()
        footingDepth = st.footingDepthM.toString()
        beamWidth = st.beamWidthMm.toString()
        beamDepth = st.beamDepthMm.toString()
        beamLength = st.beamLengthM.toString()
        steelPerM3 = st.steelKgPerM3.toString()
        openingRows.clear()
        settings.openingTypes.forEach {
            openingRows.add(OpeningRow(it.id, it.kind, it.name, it.widthM.toString(), it.heightM.toString()))
        }
        client = doc?.client ?: ""
        location = doc?.location ?: ""
        floorName = doc?.floorName ?: ""
        preparedBy = doc?.preparedBy ?: ""
        checkedBy = doc?.checkedBy ?: ""
        revision = doc?.revision ?: ""
        notes = doc?.notes ?: ""
    }

    fun refreshReadiness() {
        val d = plan
        val b = boq ?: return
        readiness = BoqReadiness.evaluate(d, b.settings, b.items, accepted)
    }

    LaunchedEffect(projectId) {
        val doc = app.documentStore.load(projectId)
        plan = doc
        val library = app.rateRepository.load()
        val existing = app.boqRepository.load(projectId)
        val base = existing ?: BoqDocument(projectId)
        accepted = base.acceptedGaps
        if (doc != null) {
            val computed = QuantityEngine.compute(doc, base.settings)
            val rated = BoqRates.apply(computed.items, library)
            val merged = BoqMerge.merge(base.items, rated)
            val updated = base.copy(items = merged)
            boq = updated
            app.boqRepository.save(updated)
        } else {
            boq = base
        }
        boq?.let { seed(it.settings, doc) }
        readiness = boq?.let {
            BoqReadiness.evaluate(doc, it.settings, it.items, accepted)
        }
        loading = false
    }

    fun num(text: String, fallback: Double): Double = text.replace(",", ".").toDoubleOrNull() ?: fallback

    fun buildSettings(): BoqSettings {
        val current = boq?.settings ?: BoqSettings()
        val types = openingRows.mapNotNull { row ->
            val w = row.width.replace(",", ".").toDoubleOrNull()
            val h = row.height.replace(",", ".").toDoubleOrNull()
            if (w != null && h != null && w > 0 && h > 0) {
                OpeningType(row.id, row.name.ifBlank { "Opening" }, row.kind, w, h)
            } else null
        }
        return current.copy(
            standard = standard,
            currency = currency.ifBlank { Standards.defaultCurrency(standard) },
            storeyHeightM = num(storeyHeight, current.storeyHeightM),
            slabThicknessM = num(slabThickness, current.slabThicknessM),
            storeys = num(storeys, current.storeys.toDouble()).toInt().coerceAtLeast(1),
            externalWallThicknessMm = num(extThickness, current.externalWallThicknessMm),
            internalWallThicknessMm = num(intThickness, current.internalWallThicknessMm),
            wastagePercent = num(wastage, current.wastagePercent),
            contingencyPercent = num(contingency, current.contingencyPercent),
            openingTypes = if (types.isEmpty()) current.openingTypes else types,
            structural = StructuralData(
                enabled = structuralEnabled,
                foundationType = foundation,
                columnCount = num(columnCount, 0.0).toInt().coerceAtLeast(0),
                columnWidthMm = num(columnWidth, 300.0),
                columnDepthMm = num(columnDepth, 300.0),
                footingLengthM = num(footingLength, 1.2),
                footingWidthM = num(footingWidth, 1.2),
                footingDepthM = num(footingDepth, 0.3),
                beamWidthMm = num(beamWidth, 230.0),
                beamDepthMm = num(beamDepth, 450.0),
                beamLengthM = num(beamLength, 0.0),
                steelKgPerM3 = num(steelPerM3, 90.0),
            ),
        )
    }

    fun save() {
        val b = boq ?: return
        val d = plan
        scope.launch {
            val settings = buildSettings()
            val library = app.rateRepository.load()
            val computed = d?.let { QuantityEngine.compute(it, settings) }
            val rated = computed?.let { BoqRates.apply(it.items, library) } ?: emptyList()
            val reviewed = accepted + setOf(
                "storey_height", "slab", "storeys", "thickness", "opening_types", "contingency", "wastage",
            )
            val updated = b.copy(
                settings = settings,
                items = if (d != null) BoqMerge.merge(b.items, rated) else b.items,
                acceptedGaps = reviewed,
                updatedAt = System.currentTimeMillis(),
            )
            app.boqRepository.save(updated)
            if (d != null) {
                app.documentStore.save(
                    d.copy(
                        client = client,
                        location = location,
                        floorName = floorName,
                        preparedBy = preparedBy,
                        checkedBy = checkedBy,
                        revision = revision,
                        notes = notes,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
            boq = updated
            accepted = reviewed
            readiness = BoqReadiness.evaluate(d, settings, updated.items, reviewed)
            snackbar.showSnackbar("Setup saved - readiness ${readiness?.score ?: 0}%")
            nav.popBackStack()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Complete BOQ data") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
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

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                readiness?.let { r ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Readiness ${r.score}%", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${r.blocking} blocking, ${r.important} assumptions, ${r.optional} optional gaps",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            r.gaps.filter { it.severity == com.planboq.app.boq.GapSeverity.BLOCKING }
                                .forEach {
                                    Text("• ${it.title} - ${it.question}", style = MaterialTheme.typography.bodySmall)
                                }
                        }
                    }
                }
            }

            item {
                SectionCard("Plan basics") {
                    val scaleOk = plan?.scale?.calibrated == true
                    Text(
                        if (scaleOk) "Scale: ${plan?.scale?.label}" else "Scale: NOT calibrated (blocking)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (scaleOk) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                    if (!scaleOk) {
                        OutlinedButton(onClick = { nav.navigate(Routes.editor(projectId)) }) {
                            Text("Open editor to calibrate")
                        }
                    }
                    val roomCount = plan?.rooms?.count { !it.excluded } ?: 0
                    Text(
                        if (roomCount > 0) "Rooms: $roomCount measurable" else "Rooms: none (blocking)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (roomCount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                    if (roomCount == 0) {
                        OutlinedButton(
                            onClick = {
                                val d = plan ?: return@OutlinedButton
                                if (!d.scale.calibrated) {
                                    scope.launch { snackbar.showSnackbar("Calibrate the scale first") }
                                    return@OutlinedButton
                                }
                                scope.launch {
                                    detectingRooms = true
                                    val rooms = withContext(Dispatchers.Default) {
                                        app.imageProcessor.ensureOpenCv()
                                        app.roomDetector.detect(d)
                                    }
                                    if (rooms.isNotEmpty()) {
                                        val updated = d.copyWithTouch(rooms = rooms)
                                        plan = updated
                                        app.documentStore.save(updated)
                                        snackbar.showSnackbar("${rooms.size} rooms detected")
                                    } else {
                                        snackbar.showSnackbar("No rooms found - check the walls")
                                    }
                                    detectingRooms = false
                                    refreshReadiness()
                                }
                            },
                            enabled = !detectingRooms,
                        ) { Text(if (detectingRooms) "Detecting…" else "Detect rooms") }
                    }
                }
            }

            item {
                SectionCard("Measurement standard and assumptions") {
                    StandardDropdown(standard) { chosen ->
                        standard = chosen
                        currency = Standards.defaultCurrency(chosen)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("Storey height (m)", storeyHeight, { storeyHeight = it }, Modifier.weight(1f))
                        Field("Slab (m)", slabThickness, { slabThickness = it }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("Storeys", storeys, { storeys = it }, Modifier.weight(1f))
                        Field("Wastage %", wastage, { wastage = it }, Modifier.weight(1f))
                        Field("Contingency %", contingency, { contingency = it }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("Ext wall mm", extThickness, { extThickness = it }, Modifier.weight(1f))
                        Field("Int wall mm", intThickness, { intThickness = it }, Modifier.weight(1f))
                        Field("Currency", currency, { currency = it }, Modifier.weight(1f))
                    }
                }
            }

            item {
                SectionCard("Door / window types") {
                    openingRows.forEachIndexed { index, row ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Field("Name", row.name, { v -> openingRows[index] = row.copy(name = v) }, Modifier.weight(1.3f))
                            Field("W m", row.width, { v -> openingRows[index] = row.copy(width = v) }, Modifier.weight(0.7f))
                            Field("H m", row.height, { v -> openingRows[index] = row.copy(height = v) }, Modifier.weight(0.7f))
                            IconButton(onClick = { openingRows.removeAt(index) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove")
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            openingRows.add(OpeningRow(UUID.randomUUID().toString(), LayerKind.DOOR, "Door type", "0.9", "2.1"))
                        }) { Text("+ Door type") }
                        OutlinedButton(onClick = {
                            openingRows.add(OpeningRow(UUID.randomUUID().toString(), LayerKind.WINDOW, "Window type", "1.2", "1.2"))
                        }) { Text("+ Window type") }
                    }
                }
            }

            item {
                SectionCard("Structural data") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Estimate footings, columns, beams, steel", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Uses the inputs below; results are tagged ASSUMED.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = structuralEnabled, onCheckedChange = { structuralEnabled = it })
                    }
                    if (structuralEnabled) {
                        FoundationDropdown(foundation) { foundation = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field("Columns", columnCount, { columnCount = it }, Modifier.weight(1f))
                            Field("Col width mm", columnWidth, { columnWidth = it }, Modifier.weight(1f))
                            Field("Col depth mm", columnDepth, { columnDepth = it }, Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field("Footing L m", footingLength, { footingLength = it }, Modifier.weight(1f))
                            Field("Footing W m", footingWidth, { footingWidth = it }, Modifier.weight(1f))
                            Field("Footing D m", footingDepth, { footingDepth = it }, Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field("Beam width mm", beamWidth, { beamWidth = it }, Modifier.weight(1f))
                            Field("Beam depth mm", beamDepth, { beamDepth = it }, Modifier.weight(1f))
                            Field("Beam length m (0=auto)", beamLength, { beamLength = it }, Modifier.weight(1.2f))
                        }
                        Field("Steel allowance kg/m3", steelPerM3, { steelPerM3 = it }, Modifier.fillMaxWidth())
                    } else {
                        TextButton(onClick = { accepted = accepted + "structural" }) { Text("Skip structural estimate for now") }
                    }
                }
            }

            item {
                SectionCard("Rates") {
                    val missing = boq?.items?.filter { it.rate == 0.0 }?.map { it.code }?.distinct().orEmpty()
                    if (missing.isEmpty()) {
                        Text("All items have rates", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(
                            "${missing.size} item(s) without rates: ${missing.joinToString(", ")}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { nav.navigate(Routes.RATES) }) { Text("Open rate library") }
                            TextButton(onClick = { accepted = accepted + "rates" }) { Text("Skip for now") }
                        }
                    }
                }
            }

            item {
                SectionCard("Project information") {
                    Field("Client", client, { client = it }, Modifier.fillMaxWidth())
                    Field("Location / site", location, { location = it }, Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("Floor", floorName, { floorName = it }, Modifier.weight(1f))
                        Field("Revision", revision, { revision = it }, Modifier.weight(0.7f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("Prepared by", preparedBy, { preparedBy = it }, Modifier.weight(1f))
                        Field("Checked by", checkedBy, { checkedBy = it }, Modifier.weight(1f))
                    }
                    Field("Notes", notes, { notes = it }, Modifier.fillMaxWidth())
                    TextButton(onClick = { accepted = accepted + "project_info" }) { Text("Skip project information") }
                }
            }

            item {
                Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Save and rebuild BOQ")
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StandardDropdown(selected: MeasurementStandard, onSelect: (MeasurementStandard) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Measurement standard") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MeasurementStandard.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    onClick = { onSelect(entry); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FoundationDropdown(selected: FoundationType, onSelect: (FoundationType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Foundation type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FoundationType.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    onClick = { onSelect(entry); expanded = false },
                )
            }
        }
    }
}
