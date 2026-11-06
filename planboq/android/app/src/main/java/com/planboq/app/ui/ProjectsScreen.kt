package com.planboq.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Architecture
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.planboq.app.Routes
import com.planboq.app.data.repo.ProjectEntity
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(nav: NavController) {
    val app = LocalApp.current
    val projects by app.database.projectDao().observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var menuFor by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<ProjectEntity?>(null) }
    var renameText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PlanBOQ") },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.COMBINED) }) {
                        Icon(Icons.Default.Calculate, contentDescription = "Combined BOQ")
                    }
                    IconButton(onClick = { nav.navigate(Routes.PAYWALL) }) {
                        Icon(Icons.Default.Star, contentDescription = "Upgrade")
                    }
                    IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate(Routes.IMPORT) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New plan") },
            )
        },
    ) { padding ->
        if (projects.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Outlined.Architecture,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(16.dp))
                Text("No plans yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Import a DXF or vector PDF, or photograph a printed plan and trace it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                items(projects, key = { it.id }) { p ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { nav.navigate(Routes.editor(p.id)) }) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${p.sourceType} · ${DateFormat.getDateTimeInstance().format(Date(p.updatedAt))}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Box {
                                IconButton(onClick = { menuFor = p.id }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Project menu")
                                }
                                DropdownMenu(
                                    expanded = menuFor == p.id,
                                    onDismissRequest = { menuFor = null },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        onClick = {
                                            menuFor = null
                                            renameTarget = p
                                            renameText = p.name
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Duplicate") },
                                        onClick = {
                                            menuFor = null
                                            scope.launch {
                                                val copy = app.documentStore.copyProject(p.id, "${p.name} (copy)")
                                                if (copy != null) {
                                                    app.database.projectDao().upsert(
                                                        ProjectEntity(
                                                            id = copy.id,
                                                            name = copy.name,
                                                            sourceType = copy.sourceType.name,
                                                            createdAt = copy.createdAt,
                                                            updatedAt = copy.updatedAt,
                                                        ),
                                                    )
                                                }
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete") },
                                        onClick = {
                                            menuFor = null
                                            scope.launch {
                                                app.documentStore.delete(p.id)
                                                app.boqRepository.delete(p.id)
                                                app.database.projectDao().delete(p)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename project") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Project name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = renameText.trim().ifBlank { target.name }
                        scope.launch {
                            val doc = app.documentStore.load(target.id)
                            if (doc != null) {
                                app.documentStore.save(doc.copy(name = name, updatedAt = System.currentTimeMillis()))
                            }
                            app.database.projectDao().upsert(target.copy(name = name, updatedAt = System.currentTimeMillis()))
                        }
                        renameTarget = null
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
}
