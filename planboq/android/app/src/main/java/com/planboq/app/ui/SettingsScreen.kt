package com.planboq.app.ui

import android.content.pm.ApplicationInfo
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by app.settingsRepository.settings.collectAsState(initial = com.planboq.app.data.repo.Settings())

    var serverUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var syncId by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var testResult by remember {
        mutableStateOf<com.planboq.app.net.PlanBoqServer.ConnectionResult?>(null)
    }

    LaunchedEffect(settings) {
        if (!loaded) {
            serverUrl = settings.serverUrl
            apiKey = settings.apiKey
            syncId = settings.syncId
            loaded = true
        }
    }

    val isDebug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Processing server (optional)", style = MaterialTheme.typography.titleMedium)
            Text(
                "When set, photo line detection runs on your PlanBOQ server instead of the phone. " +
                    "The app falls back to on-device processing whenever the server is unreachable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("Server URL (e.g. https://plan.example.com)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(onClick = {
                scope.launch {
                    app.settingsRepository.setServer(serverUrl, apiKey)
                    app.settingsRepository.setSyncId(syncId)
                    saved = true
                }
            }) { Text(if (saved) "Saved" else "Save") }

            OutlinedButton(
                onClick = {
                    if (testing) return@OutlinedButton
                    testing = true
                    testResult = null
                    scope.launch {
                        val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            app.serverClient.checkConnection(serverUrl, apiKey)
                        }
                        testResult = res
                        testing = false
                    }
                },
                enabled = !testing && serverUrl.isNotBlank(),
            ) { Text(if (testing) "Testing…" else "Test connection") }

            testResult?.let { r ->
                val text = when {
                    !r.reachable -> "Unreachable: ${r.detail}"
                    r.keyOk == true -> "Server reachable · API key OK"
                    r.keyOk == false -> "Server reachable · API key rejected — check the key"
                    else -> "Server reachable · key not tested"
                }
                val color = when {
                    !r.reachable || r.keyOk == false -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                }
                Text(text, color = color, style = MaterialTheme.typography.bodySmall)
            }

            Text("Cloud sync (optional)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Back projects up to your processing server and restore them on another device " +
                    "using the same sync code. Keep this code private.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = syncId,
                onValueChange = { syncId = it },
                label = { Text("Sync code (min 12 characters)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        syncId = java.util.UUID.randomUUID().toString().replace("-", "").take(24)
                        scope.launch { app.settingsRepository.setSyncId(syncId) }
                    },
                ) { Text("Generate") }
                Button(
                    onClick = {
                        if (syncing) return@Button
                        syncing = true
                        syncMessage = null
                        scope.launch {
                            app.settingsRepository.setSyncId(syncId)
                            syncMessage = app.syncRepository.backup(serverUrl, apiKey, syncId).message
                            syncing = false
                        }
                    },
                    enabled = !syncing && syncId.length >= 12,
                ) { Text(if (syncing) "Syncing…" else "Back up now") }
                OutlinedButton(
                    onClick = {
                        if (syncing) return@OutlinedButton
                        syncing = true
                        syncMessage = null
                        scope.launch {
                            app.settingsRepository.setSyncId(syncId)
                            syncMessage = app.syncRepository.restore(serverUrl, apiKey, syncId).message
                            syncing = false
                        }
                    },
                    enabled = !syncing && syncId.length >= 12,
                ) { Text("Restore") }
            }
            syncMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            if (isDebug) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("DEBUG: force Pro entitlement", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "For testing without Play Console. Never visible in release builds.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.debugProOverride,
                        onCheckedChange = { on ->
                            scope.launch { app.settingsRepository.setDebugProOverride(on) }
                        },
                    )
                }
            }

            Text("About", style = MaterialTheme.typography.titleMedium)
            Text(
                "PlanBOQ turns floor plans into clean vector drawings.\n\n" +
                    "DISCLAIMER: All dimensions and quantities produced by this app are estimates. " +
                    "Traced images are especially approximate. Always verify against site measurements " +
                    "and drawings before construction or ordering materials.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
