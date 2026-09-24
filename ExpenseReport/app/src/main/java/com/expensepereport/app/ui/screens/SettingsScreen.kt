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
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(settingsRepository: AppSettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val currentUrl by settingsRepository.supabaseUrlFlow.collectAsState(initial = "")
    val currentKey by settingsRepository.supabaseKeyFlow.collectAsState(initial = "")

    var urlInput by remember(currentUrl) { mutableStateOf(currentUrl) }
    var keyInput by remember(currentKey) { mutableStateOf(currentKey) }
    var hasTemplate by remember { mutableStateOf(settingsRepository.hasCustomExcelTemplate()) }

    var statusMessage by remember { mutableStateOf("") }

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
                        statusMessage = "Modello Excel salvato con successo!"
                    } else {
                        statusMessage = "Errore durante il salvataggio del modello."
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

        OutlinedTextField(
            value = urlInput,
            onValueChange = { urlInput = it },
            label = { Text("Supabase URL") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text("Supabase Anon Key") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                scope.launch {
                    settingsRepository.saveSupabaseCredentials(urlInput, keyInput)
                    statusMessage = "Credenziali Supabase salvate!"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("💾 Salva Credenziali Supabase")
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
