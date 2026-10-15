package com.expensepereport.app.ui.screens

import android.app.Activity
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
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // Google Sign-In Launcher
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(Exception::class.java)
                googleAccount = account
                statusMessage = "🎉 Accesso Google effettuato: ${account?.email}"
            } catch (e: Exception) {
                statusMessage = "❌ Accesso Google fallito: ${e.localizedMessage ?: e.message}"
            }
        }
    }

    // Database SQLite Import Launcher
    val dbImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                isPerformingAction = true
                statusMessage = "Importazione database SQLite in corso..."
                val success = spesaRepository.importDatabaseFromUri(uri)
                isPerformingAction = false
                if (success) {
                    statusMessage = "🎉 Database SQLite importato con successo nell'App!"
                } else {
                    statusMessage = "❌ Errore durante l'importazione del database."
                }
            }
        }
    }

    // Excel Template Launcher
    val excelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
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
        Text("☁️ Google Drive & Account", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))

        if (googleAccount != null) {
            Text("✅ Connesso come: ${googleAccount?.email}", color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            val account = googleAccount ?: return@launch
                            val url = googleDriveService.getAttachmentsFolderUrl(account)
                            if (url != null) {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    statusMessage = "Impossibile aprire il browser/Drive app."
                                }
                            } else {
                                statusMessage = "Errore nel recupero dell'URL della cartella GDrive."
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("📂 Cartella GDrive")
                }

                OutlinedButton(
                    onClick = {
                        val client = GoogleSignIn.getClient(context, GoogleDriveService.getGoogleSignInOptions())
                        client.signOut().addOnCompleteListener {
                            googleAccount = null
                            statusMessage = "Disconnesso da Google."
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Disconnetti")
                }
            }
        } else {
            Text("⚠️ Nessun account Google connesso. Accedi per salvare allegati e backup su Google Drive.", color = MaterialTheme.colorScheme.error)
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
                        Text(String.format("%02d", selectedHour))
                    }
                    Text(" : ")
                    OutlinedButton(onClick = {
                        selectedMinute = (selectedMinute + 15) % 60
                        scope.launch {
                            settingsRepository.saveBackupSettings(true, selectedHour, selectedMinute)
                        }
                    }) {
                        Text(String.format("%02d", selectedMinute))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                val account = googleAccount
                if (account == null) {
                    statusMessage = "⚠️ Accedi prima con Google per eseguire il backup."
                    return@Button
                }
                scope.launch {
                    isPerformingAction = true
                    statusMessage = "Esecuzione backup manuale su Google Drive..."
                    val dbFile = spesaRepository.getDatabaseFile()
                    val success = googleDriveService.uploadDatabaseBackup(account, dbFile)
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
                val account = googleAccount
                if (account == null) {
                    statusMessage = "⚠️ Accedi prima con Google per recuperare i backup."
                    return@OutlinedButton
                }
                scope.launch {
                    isPerformingAction = true
                    statusMessage = "Caricamento elenco backup da Google Drive..."
                    availableBackups = googleDriveService.listBackups(account)
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
            Text("📥 Importa File Database SQLite (.db)")
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
                Column {
                    Text("⚠️ Il ripristino sovrascriverà i dati locali con il backup selezionato:")
                    Spacer(modifier = Modifier.height(8.dp))
                    availableBackups.forEach { file ->
                        TextButton(
                            onClick = {
                                showRestoreDialog = false
                                val account = googleAccount ?: return@TextButton
                                scope.launch {
                                    isPerformingAction = true
                                    statusMessage = "Scaricamento e ripristino backup in corso..."
                                    val tempFile = java.io.File(context.cacheDir, "temp_restore.db")
                                    val downloaded = googleDriveService.downloadBackup(account, file.id, tempFile)
                                    if (downloaded) {
                                        val restored = spesaRepository.restoreDatabaseFromFile(tempFile)
                                        tempFile.delete()
                                        statusMessage = if (restored) "🎉 Database ripristinato con successo!" else "❌ Errore durante il ripristino del database."
                                    } else {
                                        statusMessage = "❌ Impossibile scaricare il file dal backup su GDrive."
                                    }
                                    isPerformingAction = false
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
