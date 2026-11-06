package com.planboq.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.planboq.app.data.geometry.Layer
import com.planboq.app.data.geometry.LayerKind
import com.planboq.app.data.geometry.PlanDocument
import com.planboq.app.data.geometry.Room
import com.planboq.app.data.geometry.RoomKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayersSheet(
    document: PlanDocument,
    currentLayerId: String,
    onDismiss: () -> Unit,
    onSelectCurrent: (String) -> Unit,
    onChangeKind: (String, LayerKind) -> Unit,
    onAddLayer: () -> Unit,
    onEditThickness: (Layer) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Layers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onAddLayer) { Text("Add") }
            }
            Text(
                "New lines use the current layer. Wall thickness feeds the BOQ and room detection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            document.layers.forEach { layer ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelectCurrent(layer.id) }
                        .padding(vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .padding(end = 12.dp)
                                .size(18.dp)
                                .background(Color(layer.colorArgb), CircleShape),
                        )
                        Text(
                            layer.name + if (layer.id == currentLayerId) "  (current)" else "",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        KindDropdown(selected = layer.kind, onSelect = { onChangeKind(layer.id, it) })
                    }
                    val isWall = layer.kind == LayerKind.EXTERNAL_WALL || layer.kind == LayerKind.INTERNAL_WALL
                    if (isWall) {
                        Text(
                            if (layer.thicknessMm != null) {
                                "Thickness: ${layer.thicknessMm!!.toInt()} mm (tap to change)"
                            } else {
                                "Thickness: default (tap to set)"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = 30.dp, top = 2.dp)
                                .clickable { onEditThickness(layer) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KindDropdown(selected: LayerKind, onSelect: (LayerKind) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.name.lowercase().replace('_', ' '),
            onValueChange = {},
            readOnly = true,
            textStyle = MaterialTheme.typography.bodySmall,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().width(150.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LayerKind.entries.forEach { kind ->
                DropdownMenuItem(
                    text = { Text(kind.name.lowercase().replace('_', ' ')) },
                    onClick = { onSelect(kind); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomsSheet(
    document: PlanDocument,
    onDismiss: () -> Unit,
    onSelect: (Room) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("Rooms", style = MaterialTheme.typography.titleMedium)
            Text(
                "Tap a room to rename it, change its type, exclude it from the BOQ or delete it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (document.rooms.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("No rooms detected yet - calibrate the scale or re-import the plan.")
            }
            document.rooms.forEach { room ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(room) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            room.name + if (room.excluded) "  (excluded)" else "",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            room.kind.name.lowercase().replace('_', ' '),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val area = if (document.scale.calibrated) {
                        room.areaM2(document.scale.realMmPerPlanUnit)
                    } else null
                    Text(
                        area?.let { "%.1f m²".format(it) } ?: "-",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomEditorSheet(
    room: Room,
    scaleRatio: Double,
    calibrated: Boolean,
    onDismiss: () -> Unit,
    onSave: (Room) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(room.id) { mutableStateOf(room.name) }
    var kind by remember(room.id) { mutableStateOf(room.kind) }
    var excluded by remember(room.id) { mutableStateOf(room.excluded) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Room", style = MaterialTheme.typography.titleMedium)
            val area = if (calibrated) room.areaM2(scaleRatio) else null
            Text(
                if (area != null) "Area: %.1f m²".format(area) else "Area: calibrate the scale to see m²",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Room name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            RoomKindDropdown(selected = kind, onSelect = { kind = it })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Exclude from BOQ", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Keeps the room on the drawing, removes its floor area from quantities.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = excluded, onCheckedChange = { excluded = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDelete) { Text("Delete") }
                Button(
                    onClick = { onSave(room.copy(name = name.ifBlank { room.name }, kind = kind, excluded = excluded)) },
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomKindDropdown(selected: RoomKind, onSelect: (RoomKind) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.name.lowercase(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Room type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RoomKind.entries.forEach { k ->
                DropdownMenuItem(
                    text = { Text(k.name.lowercase()) },
                    onClick = { onSelect(k); expanded = false },
                )
            }
        }
    }
}

@Composable
fun ProjectInfoDialog(
    document: PlanDocument,
    onDismiss: () -> Unit,
    onSave: (
        name: String, client: String, location: String, floor: String, notes: String,
        preparedBy: String, checkedBy: String, revision: String,
    ) -> Unit,
) {
    var name by remember { mutableStateOf(document.name) }
    var client by remember { mutableStateOf(document.client) }
    var location by remember { mutableStateOf(document.location) }
    var floor by remember { mutableStateOf(document.floorName) }
    var notes by remember { mutableStateOf(document.notes) }
    var preparedBy by remember { mutableStateOf(document.preparedBy) }
    var checkedBy by remember { mutableStateOf(document.checkedBy) }
    var revision by remember { mutableStateOf(document.revision) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Project information") },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .height(360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("Project name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(client, { client = it }, label = { Text("Client") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(location, { location = it }, label = { Text("Location / site") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(floor, { floor = it }, label = { Text("Floor (e.g. Ground floor)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(preparedBy, { preparedBy = it }, label = { Text("Prepared by") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(checkedBy, { checkedBy = it }, label = { Text("Checked by") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(revision, { revision = it }, label = { Text("Revision (e.g. A)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes (printed on deliverables)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, client, location, floor, notes, preparedBy, checkedBy, revision) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun NumberInputDialog(
    title: String,
    label: String,
    initial: String,
    allowBlank: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Double?) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val value = text.replace(",", ".").toDoubleOrNull()
    val valid = (text.isBlank() && allowBlank) || (value != null && value > 0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Value") },
                    isError = text.isNotBlank() && (value == null || value <= 0),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(if (text.isBlank()) null else value) },
                enabled = valid,
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ScaleDialog(
    title: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    NumberInputDialog(
        title = title,
        label = label,
        initial = "",
        allowBlank = false,
        onDismiss = onDismiss,
        onConfirm = { value -> value?.let(onConfirm) },
    )
}
