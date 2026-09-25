package com.expensepereport.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.expensepereport.app.data.AppSettingsRepository
import com.expensepereport.app.data.SupabaseService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    settingsRepository: AppSettingsRepository,
    supabaseService: SupabaseService
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val currentUrl by settingsRepository.supabaseUrlFlow.collectAsState(initial = "")
    val currentKey by settingsRepository.supabaseKeyFlow.collectAsState(initial = "")

    var hasCredentials by remember(currentUrl, currentKey) {
        mutableStateOf(
            currentUrl.isNotBlank() &&
            currentUrl != AppSettingsRepository.DEFAULT_SUPABASE_URL &&
            currentKey.isNotBlank() &&
            currentKey != AppSettingsRepository.DEFAULT_SUPABASE_KEY
        )
    }

    var hasTemplate by remember { mutableStateOf(settingsRepository.hasCustomExcelTemplate()) }

    var isTestingConnection by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }

    val supabaseTxtPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                var parsedUrl = ""
                var parsedKey = ""

                content.lines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("SUPABASE_URL")) {
                        parsedUrl = trimmed.substringAfter("=").trim().trim('"').trim('\'')
                    } else if (trimmed.startsWith("SUPABASE_KEY")) {
                        parsedKey = trimmed.substringAfter("=").trim().trim('"').trim('\'')
                    }
                }

                if (parsedUrl.isNotBlank() && parsedKey.isNotBlank()) {
                    scope.launch {
                        settingsRepository.saveSupabaseCredentials(parsedUrl, parsedKey)
                        hasCredentials = true
                        statusMessage = "✅ File SUPABASE.txt caricato e credenziali salvate con successo!"
                    }
                } else {
                    statusMessage = "⚠️ Struttura file non valida. Assicurati che contenga SUPABASE_URL e SUPABASE_KEY."
                }
            } catch (e: Exception) {
                statusMessage = "Errore lettura file SUPABASE.txt: ${e.message}"
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
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
    ) {
        Text("⚙️ Impostazioni App", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        Text("🔐 Configurazione SUPABASE", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))

        if (hasCredentials) {
            Text("✅ Configurazione SUPABASE attiva.", color = MaterialTheme.colorScheme.primary)
        } else {
            Text("⚠️ Nessuna configurazione SUPABASE valida. Carica il file SUPABASE.txt.", color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = { supabaseTxtPicker.launch("text/plain") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("📄 Carica File SUPABASE.txt")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                scope.launch {
                    isTestingConnection = true
                    statusMessage = "Verifica connessione a SUPABASE in corso..."
                    val success = withContext(Dispatchers.IO) {
                        try {
                            supabaseService.getSpese()
                            true
                        } catch (e: Exception) {
                            e.printStackTrace()
                            false
                        }
                    }
                    isTestingConnection = false
                    if (success) {
                        statusMessage = "🎉 Connessione a SUPABASE riuscita con successo!"
                    } else {
                        statusMessage = "❌ Errore di connessione a SUPABASE! Verifica URL e Key."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isTestingConnection
        ) {
            Text(if (isTestingConnection) "Verifica in corso..." else "⚡ Test Connessione SUPABASE")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

        Text("📊 Modello Excel Note Spese", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))

        if (hasTemplate) {
            Text("✅ Modello Excel personalizzato caricato e attivo.", color = MaterialTheme.colorScheme.primary)
        } else {
            Text("⚠️ Nessun modello Excel caricato. Carica il file 'Note spese 2026.xlsx' per abilitare l'export Excel.", color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = {
                filePickerLauncher.launch("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("📁 Carica / Aggiorna Modello Excel (.xlsx)")
        }

        if (statusMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(statusMessage, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
