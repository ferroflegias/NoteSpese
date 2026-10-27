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
import androidx.compose.ui.unit.dp
import com.expensepereport.app.data.AppSettingsRepository
import com.expensepereport.app.data.GoogleDriveService
import com.expensepereport.app.data.SpesaRepository
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.google.api.services.drive.model.File
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SettingsScreen(
    settingsRepository: AppSettingsRepository,
    spesaRepository: SpesaRepository,
    googleDriveService: GoogleDriveService
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var googleAccount by remember {
        mutableStateOf(GoogleDriveService.getLastSignedInAccount(context))
    }

    val savedEmail by settingsRepository.googleAccountEmailFlow.collectAsState(initial = "")
    val activeEmail = googleAccount?.email ?: savedEmail

    var hasTemplate by remember { mutableStateOf(settingsRepository.hasCustomExcelTemplate()) }

    val backupEnabled by settingsRepository.backupEnabledFlow.collectAsState(initial = true)
    val backupHour by settingsRepository.backupHourFlow.collectAsState(initial = 2)
    val backupMinute by settingsRepository.backupMinuteFlow.collectAsState(initial = 0)

    var selectedHour by remember(backupHour) { mutableStateOf(backupHour) }
    var selectedMinute by remember(backupMinute) { mutableStateOf(backupMinute) }

    var isPerformingAction by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }

    var availableBackups by remember { mutableStateOf<List<File>>(emptyList()) }
    var showRestoreDialog by remember { mutableStateOf(false) }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.data != null) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                googleAccount = account
                account?.email?.let { email ->
                    scope.launch { settingsRepository.saveGoogleAccountEmail(email) }
                }
                statusMessage = "🎉 Accesso Google effettuato con successo: ${account?.email}"
            } catch (e: ApiException) {
                val errorReason = when (e.statusCode) {
                    10 -> "10 (DEVELOPER_ERROR: Verifica che l'impronta SHA-1 del keystore e il Package Name siano registrati su Google Cloud Console e che lo stato della Schermata di consenso OAuth sia 'In produzione')"
                    12500 -> "12500 (Configurazione Client ID OAuth richiesta su Google Cloud Console)"
                    7 -> "7 (NETWORK_ERROR: Nessuna connessione ad Internet)"
                    4 -> "4 (SIGN_IN_REQUIRED)"
                    else -> "Codice errore Google: ${e.statusCode}"
                }
                statusMessage = "⚠️ Accesso Google non completato: $errorReason."
            } catch (e: Exception) {
                statusMessage = "⚠️ Errore durante l'accesso Google: ${e.localizedMessage ?: e.message}"
            }
        } else {
            statusMessage = "⚠️ Selezione account annullata o non riuscita."
        }
    }

    val dbImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                isPerformingAction = true
                statusMessage = "Importazione dati in corso..."
                val success = spesaRepository.importDatabaseFromUri(uri)
                isPerformingAction = false
                if (success) {
                    statusMessage = "🎉 Dati importati con successo nell'App! Vai alla scheda Registri per consultarli."
                } else {
                    statusMessage = "❌ Impossibile importare i dati dal file selezionato."
                }
            }
        }
    }

    val excelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    val success = settingsRepository.saveExcelTemplate(bytes)
                    if (success) {
                        hasTemplate = true
                        statusMessage = "✅ Modello Excel salvato con successo!"
                    } else {
                        statusMessage = "Errore durante il salvataggio del modello Excel."
                    }
                }
            } catch (e: Exception) {
                statusMessage = "Errore lettura file: ${e.message}"
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

        // --- SECTION 1: GOOGLE ACCOUNT & DRIVE ---
        Text("☁️ Account Google & Drive", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))

        if (activeEmail.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("✅ Accesso Effettuato", style = MaterialTheme.typography.titleSmall)
                    Text(activeEmail, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            val urlToOpen = googleDriveService.getAttachmentsFolderUrl(activeEmail)
                            if (!urlToOpen.isNullOrBlank()) {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlToOpen))
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    statusMessage = "Impossibile aprire il link Google Drive."
                                }
                            } else {
                                statusMessage = "Errore durante il recupero della cartella Google Drive."
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("📂 Cartella GDrive")
                }

                OutlinedButton(
                    onClick = {
                        GoogleDriveService.signOut(context) {
                            googleAccount = null
                            scope.launch { settingsRepository.saveGoogleAccountEmail("") }
                            statusMessage = "Disconnessione effettuata."
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("🚪 Disconnetti")
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("🔑 Accesso Google Richiesto", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Accedi con il tuo Account Google per salvare automaticamente gli allegati e i backup del database sul tuo Google Drive.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    val client = GoogleSignIn.getClient(context, GoogleDriveService.getGoogleSignInOptions())
                    googleSignInLauncher.launch(client.signInIntent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🔑 Accedi con Google")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("ℹ️ Info Configurazione OAuth Google Cloud Console", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Package Name: com.expensepereport.app", style = MaterialTheme.typography.bodySmall)
                Text("Impronta SHA-1 Keystore APK:", style = MaterialTheme.typography.bodySmall)
                Text("5D:6C:3B:16:C8:09:2E:4D:4A:8E:1D:B7:F5:B2:D3:FD:AD:17:51:69", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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
                if (activeEmail.isBlank()) {
                    statusMessage = "⚠️ Accedi prima con il tuo account Google per eseguire il backup."
                    return@Button
                }
                scope.launch {
                    isPerformingAction = true
                    statusMessage = "Esecuzione backup manuale su Google Drive..."
                    val success = try {
                        val dbFile = spesaRepository.getDatabaseFileForBackup()
                        googleDriveService.uploadDatabaseBackup(activeEmail, dbFile)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                    isPerformingAction = false
                    if (success) {
                        statusMessage = "🎉 Backup salvato con successo nella cartella 'NoteSpese_Backups' su Google Drive!"
                    } else {
                        statusMessage = "❌ Errore durante il caricamento del backup su Google Drive."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isPerformingAction
        ) {
            Text("💾 Esegui Backup Ora su GDrive")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                if (activeEmail.isBlank()) {
                    statusMessage = "⚠️ Accedi prima con il tuo account Google per recuperare i backup."
                    return@OutlinedButton
                }
                scope.launch {
                    isPerformingAction = true
                    statusMessage = "Caricamento elenco backup da Google Drive..."
                    availableBackups = googleDriveService.listBackups(activeEmail)
                    isPerformingAction = false
                    if (availableBackups.isNotEmpty()) {
                        showRestoreDialog = true
                    } else {
                        statusMessage = "Nessun backup trovato nella cartella 'NoteSpese_Backups' su GDrive."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isPerformingAction
        ) {
            Text("🔄 Ripristina Database da Backup GDrive")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        // --- SECTION 3: IMPORTAZIONE LOCALE E MODELLO EXCEL ---
        Text("📥 Importazione Dati Locale", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = { dbImportLauncher.launch("*/*") },
            modifier = Modifier.fillMaxWidth()
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
                    availableBackups.forEach { file ->
                        TextButton(
                            onClick = {
                                showRestoreDialog = false
                                scope.launch {
                                    isPerformingAction = true
                                    statusMessage = "Scaricamento e ripristino backup in corso..."
                                    val tempFile = java.io.File(context.cacheDir, "temp_restore.db")
                                    try {
                                        val downloaded = googleDriveService.downloadBackup(activeEmail, file.id, tempFile)
                                        statusMessage = if (!downloaded) {
                                            "❌ Impossibile scaricare il file dal backup su GDrive."
                                        } else if (spesaRepository.importFromFile(tempFile)) {
                                            "🎉 Database ripristinato con successo!"
                                        } else {
                                            "❌ Errore durante il ripristino del database."
                                        }
                                    } finally {
                                        tempFile.delete()
                                        isPerformingAction = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(file.name ?: file.id)
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
