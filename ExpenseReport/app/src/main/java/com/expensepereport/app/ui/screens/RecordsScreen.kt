package com.expensepereport.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.expensepereport.app.data.Spesa
import com.expensepereport.app.data.SpesaInsert
import com.expensepereport.app.data.SupabaseService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Locale

@Composable
fun RecordsScreen(supabaseService: SupabaseService) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var speseList by remember { mutableStateOf<List<Spesa>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedSpesa by remember { mutableStateOf<Spesa?>(null) }
    var statusMessage by remember { mutableStateOf("") }

    fun refreshList() {
        scope.launch {
            isLoading = true
            try {
                speseList = withContext(Dispatchers.IO) { supabaseService.getSpese() }
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
                        if (!spesa.allegatoPath.isNullOrBlank()) {
                            Text("📎 Allegato presente", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
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

        var newImageUri by remember { mutableStateOf<Uri?>(null) }
        var newPdfUri by remember { mutableStateOf<Uri?>(null) }
        var isUpdating by remember { mutableStateOf(false) }

        val imagePicker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            if (uri != null) {
                newImageUri = uri
                newPdfUri = null
            }
        }

        val pdfPicker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            if (uri != null) {
                newPdfUri = uri
                newImageUri = null
            }
        }

        AlertDialog(
            onDismissRequest = { selectedSpesa = null },
            title = { Text("Modifica Spesa #${spesa.id}") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = editDate,
                        onValueChange = { editDate = it },
                        label = { Text("Data (YYYY-MM-DD)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editDest,
                        onValueChange = { editDest = it },
                        label = { Text("Destinazione") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editScopo,
                        onValueChange = { editScopo = it },
                        label = { Text("Scopo") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editImporto,
                        onValueChange = { editImporto = it },
                        label = { Text("Importo (€)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editNote,
                        onValueChange = { editNote = it },
                        label = { Text("Note") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text("📎 Allegato Corrente:", style = MaterialTheme.typography.titleSmall)
                    if (!spesa.allegatoPath.isNullOrBlank()) {
                        val pathLower = spesa.allegatoPath.lowercase()
                        if (pathLower.endsWith(".pdf") || pathLower.contains(".pdf?")) {
                            TextButton(onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(spesa.allegatoPath))
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    // Handle browser intent error
                                }
                            }) {
                                Text("📄 Apri Documento PDF Allegato")
                            }
                        } else {
                            Image(
                                painter = rememberAsyncImagePainter(spesa.allegatoPath),
                                contentDescription = "Foto allegato",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(150.dp)
                            )
                        }
                    } else {
                        Text("Nessun allegato salvato per questa spesa.", style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Sostituisci / Aggiungi Allegato:", style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { imagePicker.launch("image/*") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("📷 Foto")
                        }
                        OutlinedButton(
                            onClick = { pdfPicker.launch("application/pdf") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("📄 PDF")
                        }
                    }

                    newImageUri?.let {
                        Text("Nuova foto selezionata", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                    newPdfUri?.let {
                        Text("Nuovo PDF selezionato", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            isUpdating = true
                            spesa.id?.let { id ->
                                withContext(Dispatchers.IO) {
                                    var finalAttachmentUrl = spesa.allegatoPath

                                    val newUri = newImageUri ?: newPdfUri
                                    if (newUri != null) {
                                        try {
                                            val inputStream: InputStream? = context.contentResolver.openInputStream(newUri)
                                            val bytes = inputStream?.readBytes()
                                            if (bytes != null) {
                                                val ext = if (newPdfUri != null) "pdf" else "jpg"
                                                val mime = if (newPdfUri != null) "application/pdf" else "image/jpeg"

                                                // Delete old attachment if present
                                                if (!spesa.allegatoPath.isNullOrBlank()) {
                                                    supabaseService.deleteAttachment(spesa.allegatoPath)
                                                }

                                                finalAttachmentUrl = supabaseService.uploadAttachment(
                                                    fileBytes = bytes,
                                                    dataSpesa = editDate,
                                                    extension = ext,
                                                    contentType = mime
                                                )
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }

                                    val updatedSpesa = SpesaInsert(
                                        data = editDate,
                                        destinazione = editDest.ifBlank { null },
                                        scopo = editScopo.ifBlank { null },
                                        categoria = spesa.categoria,
                                        metodoPagamento = spesa.metodoPagamento,
                                        importo = editImporto.toDoubleOrNull() ?: spesa.importo,
                                        note = editNote.ifBlank { null },
                                        allegatoPath = finalAttachmentUrl,
                                        valutaStraniera = spesa.valutaStraniera
                                    )
                                    supabaseService.updateSpesa(id, updatedSpesa)
                                }
                                selectedSpesa = null
                                refreshList()
                            }
                            isUpdating = false
                        }
                    },
                    enabled = !isUpdating
                ) {
                    Text(if (isUpdating) "Salvataggio..." else "Salva")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            spesa.id?.let { id ->
                                withContext(Dispatchers.IO) {
                                    spesa.allegatoPath?.let { path ->
                                        supabaseService.deleteAttachment(path)
                                    }
                                    supabaseService.deleteSpesa(id)
                                }
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
