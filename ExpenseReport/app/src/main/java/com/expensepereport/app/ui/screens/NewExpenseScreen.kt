package com.expensepereport.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
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
import com.expensepereport.app.data.SupabaseService
import com.expensepereport.app.util.OcrAnalyzer
import com.expensepereport.app.util.OcrResult
import kotlinx.coroutines.launch
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun NewExpenseScreen(supabaseService: SupabaseService) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var pdfUri by remember { mutableStateOf<Uri?>(null) }

    var isAnalyzing by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    var dateInput by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())) }
    var destInput by remember { mutableStateOf("") }
    var scopoInput by remember { mutableStateOf("") }

    val categoryOptions = listOf("Bar/Rist/Alb", "Parcheggio/Taxi", "Carburante", "Telepass", "Nolo", "Altro")
    var selectedCategory by remember { mutableStateOf("Bar/Rist/Alb") }

    val paymentOptions = listOf("CC", "Contanti", "Carta Carburante")
    var selectedPayment by remember { mutableStateOf("CC") }

    var amountInput by remember { mutableStateOf("") }
    var isForeignCurrency by remember { mutableStateOf(false) }
    var noteInput by remember { mutableStateOf("") }

    var statusMessage by remember { mutableStateOf("") }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            imageUri = uri
            pdfUri = null
        }
    }

    val pdfPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            pdfUri = uri
            imageUri = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("➕ Registra Nuova Spesa", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(12.dp))

        Text("Step 1: Carica Allegato (Foto o PDF)", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { photoPicker.launch("image/*") },
                modifier = Modifier.weight(1f)
            ) {
                Text("📷 Scegli Foto")
            }
            OutlinedButton(
                onClick = { pdfPicker.launch("application/pdf") },
                modifier = Modifier.weight(1f)
            ) {
                Text("📄 Scegli PDF")
            }
        }

        imageUri?.let { uri ->
            Spacer(modifier = Modifier.height(8.dp))
            Image(
                painter = rememberAsyncImagePainter(uri),
                contentDescription = "Anteprima",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        isAnalyzing = true
                        val ocrRes = OcrAnalyzer.analyzeReceipt(context, uri)
                        isAnalyzing = false
                        if (ocrRes != null) {
                            dateInput = ocrRes.dataStr
                            destInput = ocrRes.destinazione
                            amountInput = String.format(Locale.US, "%.2f", ocrRes.importo)
                            if (categoryOptions.contains(ocrRes.categoriaSuggerita)) {
                                selectedCategory = ocrRes.categoriaSuggerita
                            }
                            if (paymentOptions.contains(ocrRes.pagamentoSuggerito)) {
                                selectedPayment = ocrRes.pagamentoSuggerito
                            }
                            noteInput = ocrRes.noteExtracted
                            statusMessage = "✅ Dati estratti con successo tramite OCR ML Kit!"
                        } else {
                            statusMessage = "⚠️ Nessun testo rilevato nella foto."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isAnalyzing
            ) {
                Text(if (isAnalyzing) "Analisi OCR in corso..." else "🔍 Estrai Testo con OCR Locale ML Kit")
            }
        }

        pdfUri?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text("📄 PDF Selezionato: $it", style = MaterialTheme.typography.bodyMedium)
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        Text("Step 2: Dati Spesa", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = dateInput,
            onValueChange = { dateInput = it },
            label = { Text("Data (YYYY-MM-DD)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = destInput,
            onValueChange = { destInput = it },
            label = { Text("Destinazione / Esercente") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = scopoInput,
            onValueChange = { scopoInput = it },
            label = { Text("Scopo") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        Text("Categoria:", style = MaterialTheme.typography.bodyMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            categoryOptions.take(3).forEach { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = cat },
                    label = { Text(cat) }
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            categoryOptions.drop(3).forEach { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = cat },
                    label = { Text(cat) }
                )
            }
        }

        if (selectedCategory != "Telepass") {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Metodo Pagamento:", style = MaterialTheme.typography.bodyMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                paymentOptions.forEach { pay ->
                    FilterChip(
                        selected = selectedPayment == pay,
                        onClick = { selectedPayment = pay },
                        label = { Text(pay) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = amountInput,
                onValueChange = { amountInput = it },
                label = { Text("Importo (€)") },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = isForeignCurrency,
                    onCheckedChange = { isForeignCurrency = it }
                )
                Text("Non-€")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = noteInput,
            onValueChange = { noteInput = it },
            label = { Text("Note") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                scope.launch {
                    val doubleAmount = amountInput.replace(",", ".").toDoubleOrNull() ?: 0.0
                    if (doubleAmount <= 0.0 && selectedCategory != "Telepass") {
                        statusMessage = "⚠️ Inserisci un importo valido!"
                        return@launch
                    }

                    isSaving = true

                    val catKey = when (selectedCategory) {
                        "Bar/Rist/Alb" -> if (selectedPayment == "CC") "RISTORANTI_CC" else "RISTORANTI_CONTANTI"
                        "Parcheggio/Taxi" -> if (selectedPayment == "CC") "PARCHEGGI_CC" else "PARCHEGGI_CONTANTI"
                        "Carburante" -> if (selectedPayment == "Carta Carburante") "CARBURANTE_CARTA" else "CARBURANTE_CC"
                        "Telepass" -> "TELEPASS"
                        "Nolo" -> "NOLO"
                        else -> "ALTRO"
                    }

                    val metodoStr = if (selectedCategory == "Telepass") null else when (selectedPayment) {
                        "CC" -> "CC (Carta)"
                        "Contanti" -> "Contanti"
                        else -> "Carta Carburante"
                    }

                    var attachmentUrl: String? = null

                    val currentUri = imageUri ?: pdfUri
                    if (currentUri != null) {
                        try {
                            val inputStream: InputStream? = context.contentResolver.openInputStream(currentUri)
                            val fileBytes = inputStream?.readBytes()
                            if (fileBytes != null) {
                                val ext = if (pdfUri != null) "pdf" else "jpg"
                                val mime = if (pdfUri != null) "application/pdf" else "image/jpeg"

                                attachmentUrl = supabaseService.uploadAttachment(
                                    fileBytes = fileBytes,
                                    dataSpesa = dateInput,
                                    extension = ext,
                                    contentType = mime
                                )
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    val spesa = Spesa(
                        data = dateInput,
                        destinazione = destInput.ifBlank { null },
                        scopo = scopoInput.ifBlank { null },
                        categoria = catKey,
                        metodoPagamento = metodoStr,
                        importo = doubleAmount,
                        note = noteInput.ifBlank { null },
                        allegatoPath = attachmentUrl,
                        valutaStraniera = if (isForeignCurrency) 1 else 0
                    )

                    val success = supabaseService.insertSpesa(spesa)
                    isSaving = false

                    if (success) {
                        statusMessage = "🎉 Spesa registrata con successo su Supabase!"
                        destInput = ""
                        scopoInput = ""
                        amountInput = ""
                        noteInput = ""
                        imageUri = null
                        pdfUri = null
                    } else {
                        statusMessage = "❌ Errore durante il salvataggio su Supabase."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSaving
        ) {
            Text(if (isSaving) "Salvataggio..." else "💾 Salva Spesa su Supabase")
        }

        if (statusMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(statusMessage, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
