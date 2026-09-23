package com.expensepereport.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.expensepereport.app.data.Spesa
import com.expensepereport.app.data.SupabaseService
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun RecordsScreen(supabaseService: SupabaseService) {
    val scope = rememberCoroutineScope()

    var speseList by remember { mutableStateOf<List<Spesa>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedSpesa by remember { mutableStateOf<Spesa?>(null) }
    var statusMessage by remember { mutableStateOf("") }

    fun refreshList() {
        scope.launch {
            isLoading = true
            try {
                speseList = supabaseService.getSpese()
            } catch (e: Exception) {
                statusMessage = "Errore caricamento spese: ${e.message}"
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("📑 Registri / Modifica Spese", style = MaterialTheme.typography.headlineMedium)
            IconButton(onClick = { refreshList() }) {
                Text("🔄")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (statusMessage.isNotEmpty()) {
            Text(statusMessage, color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(speseList) { spesa ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedSpesa = spesa },
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("ID #${spesa.id ?: "-"} | ${spesa.data}", style = MaterialTheme.typography.titleMedium)
                            Text("€ ${String.format(Locale.US, "%.2f", spesa.importo)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Text("Destinazione: ${spesa.destinazione ?: "-"}", style = MaterialTheme.typography.bodyMedium)
                        Text("Cat: ${spesa.categoria} | Pag: ${spesa.metodoPagamento ?: "Standard"}", style = MaterialTheme.typography.bodySmall)
                        if (!spesa.note.isNullOrBlank()) {
                            Text("Note: ${spesa.note}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    // Edit Dialog
    selectedSpesa?.let { spesa ->
        var editDate by remember { mutableStateOf(spesa.data) }
        var editDest by remember { mutableStateOf(spesa.destinazione ?: "") }
        var editScopo by remember { mutableStateOf(spesa.scopo ?: "") }
        var editImporto by remember { mutableStateOf(spesa.importo.toString()) }
        var editNote by remember { mutableStateOf(spesa.note ?: "") }

        AlertDialog(
            onDismissRequest = { selectedSpesa = null },
            title = { Text("Modifica Spesa #${spesa.id}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editDate,
                        onValueChange = { editDate = it },
                        label = { Text("Data (YYYY-MM-DD)") }
                    )
                    OutlinedTextField(
                        value = editDest,
                        onValueChange = { editDest = it },
                        label = { Text("Destinazione") }
                    )
                    OutlinedTextField(
                        value = editScopo,
                        onValueChange = { editScopo = it },
                        label = { Text("Scopo") }
                    )
                    OutlinedTextField(
                        value = editImporto,
                        onValueChange = { editImporto = it },
                        label = { Text("Importo (€)") }
                    )
                    OutlinedTextField(
                        value = editNote,
                        onValueChange = { editNote = it },
                        label = { Text("Note") }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        spesa.id?.let { id ->
                            val updatedSpesa = spesa.copy(
                                data = editDate,
                                destinazione = editDest.ifBlank { null },
                                scopo = editScopo.ifBlank { null },
                                importo = editImporto.toDoubleOrNull() ?: spesa.importo,
                                note = editNote.ifBlank { null }
                            )
                            supabaseService.updateSpesa(id, updatedSpesa)
                            selectedSpesa = null
                            refreshList()
                        }
                    }
                }) {
                    Text("Salva")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            spesa.id?.let { id ->
                                spesa.allegatoPath?.let { path ->
                                    supabaseService.deleteAttachment(path)
                                }
                                supabaseService.deleteSpesa(id)
                                selectedSpesa = null
                                refreshList()
                            }
                        }
                    }
                ) {
                    Text("Elimina", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }
}
