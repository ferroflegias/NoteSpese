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
import com.expensepereport.app.data.AttachmentRepository
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
    dropboxService: DropboxService,
    attachmentRepository: AttachmentRepository
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

    val syncState by settingsRepository.syncStateFlow.collectAsState(initial = null)
    val deviceName = remember { settingsRepository.deviceName() }

    /** Backup chosen for a restore that needs an explicit confirmation, with the reasons. */
    var riskyRestore by remember { mutableStateOf<Pair<BackupEntry, List<String>>?>(null) }

    /** Number of attachments saved only on this device; refreshed after uploads and restores. */
    var localAttachmentsCount by remember { mutableStateOf(0) }
    var localAttachmentsRefresh by remember { mutableStateOf(0) }
    LaunchedEffect(localAttachmentsRefresh) {
        localAttachmentsCount = try {
            spesaRepository.getSpese().count { attachmentRepository.isLocalOnly(it.allegatoPath) }
        } catch (e: Exception) {
            0
        }
    }

    val dateTimeFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY) }

    /**
     * Replaces the local data with [backup]. A safety copy of the current data is uploaded
     * first: if it cannot be saved the restore is cancelled.
     */
    fun performRestore(backup: BackupEntry) {
        scope.launch {
            isPerformingAction = true
            val tempFile = File(context.cacheDir, "temp_restore.db")
            try {
                if (spesaRepository.getSpese().isNotEmpty()) {
                    statusMessage = "Salvataggio di sicurezza dei dati attuali su Dropbox..."
                    val safetySaved = try {
                        dropboxService.uploadSafetyBackup(spesaRepository.getDatabaseFileForBackup())
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                    if (!safetySaved) {
                        statusMessage = "❌ Impossibile salvare la copia di sicurezza dei dati attuali: ripristino annullato, nessun dato modificato."
                        return@launch
                    }
                }
                statusMessage = "Scaricamento e ripristino backup in corso..."
                val downloaded = dropboxService.downloadBackup(backup.path, tempFile)
                statusMessage = if (!downloaded) {
                    "❌ Impossibile scaricare il file di backup da Dropbox."
                } else {
                    val restoredAt = System.currentTimeMillis()
                    val result = spesaRepository.importFromFile(tempFile)
                    if (result == ImportResult.SUCCESS) {
                        settingsRepository.markSynced(restoredAt)
                        "🎉 Database ripristinato con successo! I dati precedenti sono salvati su Dropbox nella cartella 'Backups_PreRipristino'."
                    } else {
                        "❌ Errore durante il ripristino del database. ${result.errorMessage()}"
                    }
                }
            } finally {
                tempFile.delete()
                isPerformingAction = false
                localAttachmentsRefresh++
            }
        }
    }

    /** Restores right away, or asks for confirmation when the restore could lose data. */
    fun requestRestore(backup: BackupEntry) {
        scope.launch {
            val warnings = mutableListOf<String>()
            val state = settingsRepository.getSyncState()
            if (state.hasUnsavedChanges) {
                warnings += "Su questo telefono ci sono modifiche non ancora salvate in un backup: andrebbero perse (resterebbero solo nella copia di sicurezza)."
            } else if (state.lastSyncMs == 0L && spesaRepository.getSpese().isNotEmpty()) {
                warnings += "Su questo telefono non risulta ancora nessun backup o ripristino: i dati attuali potrebbero non essere in nessun backup."
            }
            val newest = availableBackups.firstOrNull { !it.isSafetyBackup }
            if (backup.isSafetyBackup) {
                warnings += "Hai scelto una copia di sicurezza fatta prima di un ripristino, non un backup normale."
            } else if (newest != null && newest.path != backup.path) {
                val newestDate = newest.modified?.let { dateTimeFormat.format(it) } ?: "?"
                val from = newest.deviceName?.let { " da $it" } ?: ""
                warnings += "Esiste un backup più recente ($newestDate$from): quello scelto è più vecchio."
            }
            if (warnings.isEmpty()) performRestore(backup) else riskyRestore = backup to warnings
        }
    }

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

        syncState?.let { state ->
            val lastSync = if (state.lastSyncMs > 0) dateTimeFormat.format(state.lastSyncMs) else "mai"
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (state.hasUnsavedChanges) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    }
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        if (state.hasUnsavedChanges) "🟠 Modifiche non ancora salvate in un backup" else "🟢 Tutti i dati sono in un backup",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text("Ultimo backup o ripristino su questo telefono: $lastSync", style = MaterialTheme.typography.bodySmall)
                    Text("Nome dispositivo nei backup: $deviceName", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

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

        if (localAttachmentsCount > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "📎 $localAttachmentsCount allegati sono salvati solo su questo telefono e non sono visibili dagli altri dispositivi.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(
                onClick = {
                    if (!isDropboxConnected) {
                        statusMessage = "⚠️ Collega prima Dropbox per caricare gli allegati."
                        return@OutlinedButton
                    }
                    scope.launch {
                        isPerformingAction = true
                        statusMessage = "Caricamento allegati locali su Dropbox..."
                        val result = attachmentRepository.uploadLocalAttachments(spesaRepository)
                        isPerformingAction = false
                        localAttachmentsRefresh++
                        statusMessage = if (result.failed == 0) {
                            "🎉 ${result.uploaded} allegati caricati su Dropbox. Esegui un backup per renderli visibili agli altri dispositivi."
                        } else {
                            "⚠️ ${result.uploaded} allegati caricati, ${result.failed} non caricati: riprova più tardi."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isPerformingAction
            ) {
                Text("📤 Carica su Dropbox gli Allegati Locali ($localAttachmentsCount)")
            }
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
                    availableBackups.forEachIndexed { index, backup ->
                        if (backup.isSafetyBackup && availableBackups.getOrNull(index - 1)?.isSafetyBackup != true) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("🛟 Copie di sicurezza (fatte prima di un ripristino):", style = MaterialTheme.typography.titleSmall)
                        }
                        TextButton(
                            onClick = {
                                showRestoreDialog = false
                                requestRestore(backup)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val sizeKb = String.format(Locale.ITALY, " (%.0f KB)", backup.sizeBytes / 1024.0)
                            val date = backup.modified?.let { dateTimeFormat.format(it) + " – " } ?: ""
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

    riskyRestore?.let { (backup, warnings) ->
        AlertDialog(
            onDismissRequest = { riskyRestore = null },
            title = { Text("⚠️ Ripristino rischioso") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("Backup scelto: ${backup.name}")
                    Spacer(modifier = Modifier.height(8.dp))
                    warnings.forEach { Text("• $it") }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Prima del ripristino i dati attuali vengono comunque salvati nella cartella 'Backups_PreRipristino'.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    riskyRestore = null
                    performRestore(backup)
                }) { Text("Ripristina comunque") }
            },
            dismissButton = {
                TextButton(onClick = { riskyRestore = null }) { Text("Annulla") }
            }
        )
    }
}
