package com.expensepereport.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.expensepereport.app.data.AppSettingsRepository
import com.expensepereport.app.data.BackupEntry
import com.expensepereport.app.data.DropboxService
import com.expensepereport.app.data.ImportResult
import com.expensepereport.app.data.SpesaRepository
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun SettingsScreen(
    settingsRepository: AppSettingsRepository,
    spesaRepository: SpesaRepository,
    dropboxService: DropboxService
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val dropboxAccount by settingsRepository.dropboxAccountFlow.collectAsState(initial = "")
    val isDropboxConnected = dropboxAccount.isNotBlank()

    var hasTemplate by remember { mutableStateOf(settingsRepository.hasCustomExcelTemplate()) }

    val backupEnabled by settingsRepository.backupEnabledFlow.collectAsState(initial = true)
    val backupHour by settingsRepository.backupHourFlow.collectAsState(initial = 2)
    val backupMinute by settingsRepository.backupMinuteFlow.collectAsState(initial = 0)

    var selectedHour by remember(backupHour) { mutableStateOf(backupHour) }
    var selectedMinute by remember(backupMinute) { mutableStateOf(backupMinute) }

    var isPerformingAction by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }

    var availableBackups by remember { mutableStateOf<List<BackupEntry>>(emptyList()) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showImportConfirm by remember { mutableStateOf(false) }

    // Dropbox returns to the app after the authorisation: pick up the credential on resume
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    val email = dropboxService.completeLoginIfPending()
                    if (email != null) {
                        statusMessage = "🎉 Dropbox collegato con successo: $email"
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val dbImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                isPerformingAction = true
                statusMessage = "Importazione dati in corso..."
                val result = spesaRepository.importDatabaseFromUri(uri)
                isPerformingAction = false
                statusMessage = if (result == ImportResult.SUCCESS) {
                    "🎉 Dati importati con successo nell'App! Vai alla scheda Registri per consultarli."
                } else {
                    "❌ Impossibile importare i dati dal file selezionato. ${result.errorMessage()}"
                }
            }
        }
    }

    val excelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                if (settingsRepository.saveExcelTemplate(uri)) {
                    hasTemplate = true
                    statusMessage = "✅ Modello Excel salvato con successo!"
                } else {
                    statusMessage = "❌ Impossibile salvare il modello: il file non è leggibile o non è un file Excel .xlsx."
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("⚙️ Impostazioni App", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        // --- SECTION 1: DROPBOX ---
        Text("☁️ Dropbox", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))

        if (isDropboxConnected) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("✅ Dropbox collegato", style = MaterialTheme.typography.titleSmall)
                    Text(dropboxAccount, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Allegati e backup sono salvati in Dropbox › App › (nome dell'app Dropbox), nelle cartelle 'Allegati' e 'Backups'.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.dropbox.com/home/Apps")))
                        } catch (e: Exception) {
                            statusMessage = "Impossibile aprire Dropbox."
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("📂 Apri Dropbox")
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            isPerformingAction = true
                            dropboxService.disconnect()
                            isPerformingAction = false
                            statusMessage = "Dropbox scollegato."
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isPerformingAction
                ) {
                    Text("🚪 Scollega")
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("🔑 Dropbox non collegato", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Collega Dropbox per salvare automaticamente gli allegati e i backup del database. Senza Dropbox gli allegati restano solo sul telefono.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    if (!DropboxService.isConfigured) {
                        statusMessage = "⚠️ Questo APK è stato compilato senza App key Dropbox (DROPBOX_APP_KEY): impossibile collegare Dropbox."
                        return@Button
                    }
                    try {
                        dropboxService.startLogin(context)
                    } catch (e: Exception) {
                        statusMessage = "⚠️ Impossibile avviare l'accesso a Dropbox: ${e.localizedMessage ?: e.message}"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🔗 Collega Dropbox")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        // --- SECTION 2: BACKUP AUTOMATICO & MANUALE ---
        Text("💾 Backup & Ripristino Database", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Backup Automatico Giornaliero")
            Switch(
                checked = backupEnabled,
                onCheckedChange = { enabled ->
                    scope.launch {
                        settingsRepository.saveBackupSettings(enabled, selectedHour, selectedMinute)
                    }
                }
            )
        }

        if (backupEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Orario Backup Giornaliero:")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        selectedHour = (selectedHour + 1) % 24
                        scope.launch {
                            settingsRepository.saveBackupSettings(true, selectedHour, selectedMinute)
                        }
                    }) {
                        Text(String.format(Locale.US, "%02d", selectedHour))
                    }
                    Text(" : ")
                    OutlinedButton(onClick = {
                        selectedMinute = (selectedMinute + 15) % 60
                        scope.launch {
                            settingsRepository.saveBackupSettings(true, selectedHour, selectedMinute)
                        }
                    }) {
                        Text(String.format(Locale.US, "%02d", selectedMinute))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                if (!isDropboxConnected) {
                    statusMessage = "⚠️ Collega prima Dropbox per eseguire il backup."
                    return@Button
                }
                scope.launch {
                    isPerformingAction = true
                    statusMessage = "Esecuzione backup manuale su Dropbox..."
                    val success = try {
                        val dbFile = spesaRepository.getDatabaseFileForBackup()
                        dropboxService.uploadDatabaseBackup(dbFile)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                    isPerformingAction = false
                    if (success) {
                        statusMessage = "🎉 Backup salvato con successo nella cartella 'Backups' su Dropbox!"
                    } else {
                        statusMessage = "❌ Errore durante il caricamento del backup su Dropbox."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isPerformingAction
        ) {
            Text("💾 Esegui Backup Ora su Dropbox")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                if (!isDropboxConnected) {
                    statusMessage = "⚠️ Collega prima Dropbox per recuperare i backup."
                    return@OutlinedButton
                }
                scope.launch {
                    isPerformingAction = true
                    statusMessage = "Caricamento elenco backup da Dropbox..."
                    availableBackups = dropboxService.listBackups()
                    isPerformingAction = false
                    if (availableBackups.isNotEmpty()) {
                        showRestoreDialog = true
                    } else {
                        statusMessage = "Nessun backup trovato nella cartella 'Backups' su Dropbox."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isPerformingAction
        ) {
            Text("🔄 Ripristina Database da Backup Dropbox")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        // --- SECTION 3: IMPORTAZIONE LOCALE E MODELLO EXCEL ---
        Text("📥 Importazione Dati Locale", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = { showImportConfirm = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isPerformingAction
        ) {
            Text("📥 Importa File Database (.db / .json)")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("📊 Modello Excel Note Spese", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))

        if (hasTemplate) {
            Text("✅ Modello Excel personalizzato attivo.", color = MaterialTheme.colorScheme.primary)
        } else {
            Text("⚠️ Nessun modello Excel caricato. Carica 'Note spese 2026.xlsx'.", color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                excelPickerLauncher.launch("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("📁 Carica Modello Excel (.xlsx)")
        }

        if (statusMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(statusMessage, style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (showImportConfirm) {
        AlertDialog(
            onDismissRequest = { showImportConfirm = false },
            title = { Text("Importare un database?") },
            text = { Text("⚠️ Tutte le spese registrate nell'app verranno sostituite con quelle del file selezionato (.db o .json).") },
            confirmButton = {
                TextButton(onClick = {
                    showImportConfirm = false
                    dbImportLauncher.launch("*/*")
                }) {
                    Text("Scegli file")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirm = false }) { Text("Annulla") }
            }
        )
    }

    // Restore Backup Dialog
    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text("Seleziona Backup da Ripristinare") },
            text = {
                // Up to 30 daily backups are kept: the list must scroll
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("⚠️ Il ripristino sovrascriverà i dati locali con il backup selezionato:")
                    Spacer(modifier = Modifier.height(8.dp))
                    availableBackups.forEach { backup ->
                        TextButton(
                            onClick = {
                                showRestoreDialog = false
                                scope.launch {
                                    isPerformingAction = true
                                    statusMessage = "Scaricamento e ripristino backup in corso..."
                                    val tempFile = File(context.cacheDir, "temp_restore.db")
                                    try {
                                        val downloaded = dropboxService.downloadBackup(backup.path, tempFile)
                                        statusMessage = if (!downloaded) {
                                            "❌ Impossibile scaricare il file di backup da Dropbox."
                                        } else {
                                            val result = spesaRepository.importFromFile(tempFile)
                                            if (result == ImportResult.SUCCESS) {
                                                "🎉 Database ripristinato con successo!"
                                            } else {
                                                "❌ Errore durante il ripristino del database. ${result.errorMessage()}"
                                            }
                                        }
                                    } finally {
                                        tempFile.delete()
                                        isPerformingAction = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val sizeKb = String.format(Locale.ITALY, " (%.0f KB)", backup.sizeBytes / 1024.0)
                            val date = backup.modified?.let {
                                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(it) + " – "
                            } ?: ""
                            Text(date + backup.name + sizeKb)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) {
                    Text("Annulla")
                }
            }
        )
    }
}
