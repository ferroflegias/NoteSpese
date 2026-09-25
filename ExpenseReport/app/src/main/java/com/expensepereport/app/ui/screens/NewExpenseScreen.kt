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
import androidx.core.content.FileProvider
import coil.compose.rememberAsyncImagePainter
import com.expensepereport.app.data.SpesaInsert
import com.expensepereport.app.data.SupabaseService
import com.expensepereport.app.util.OcrAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun NewExpenseScreen(supabaseService: SupabaseService) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var pdfUri by remember { mutableStateOf<Uri?>(null) }

    var cameraTempUri by remember { mutableStateOf<Uri?>(null) }
    var showPhotoOptionsDialog by remember { mutableStateOf(false) }

    var isAnalyzing by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    var dateInput by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())) }
    var destInput by remember { mutableStateOf("") }
    var scopoInput by remember { mutableStateOf("") }

    val categoryOptions = listOf("🍴🛌🏻🍺", "🅿️🚕✈️🚅", "⛽", "🛣️", "Nolo 🚗", "Altro")
    var selectedCategory by remember { mutableStateOf("🍴🛌🏻🍺") }

    val paymentOptions = listOf("💳", "💰", "💳⛽")
    var selectedPayment by remember { mutableStateOf("💳") }

    var amountInput by remember { mutableStateOf("") }
    var isForeignCurrency by remember { mutableStateOf(false) }
    var noteInput by remember { mutableStateOf("") }

    var statusMessage by remember { mutableStateOf("") }

    // Launcher for selecting photo from Album / Gallery / Drive
    val galleryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            imageUri = uri
            pdfUri = null
        }
    }

    // Launcher for taking photo with Camera
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success && cameraTempUri != null) {
            imageUri = cameraTempUri
            pdfUri = null
        }
    }

    // Launcher for selecting PDF
    val pdfPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            pdfUri = uri
            imageUri = null
        }
    }

    fun createTempImageUri(): Uri? {
        return try {
            val tempFile = File.createTempFile("camera_photo_", ".jpg", context.cacheDir).apply {
                createNewFile()
                deleteOnExit()
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempFile)
        } catch (e: Exception) {
            e.printStackTrace()
            null
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
                onClick = { showPhotoOptionsDialog = true },
                modifier = Modifier.weight(1f)
            ) {
                Text("Foto 📷🖼️")
            }
            OutlinedButton(
                onClick = { pdfPicker.launch("application/pdf") },
                modifier = Modifier.weight(1f)
            ) {
                Text("PDF 📄")
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
                        val ocrRes = withContext(Dispatchers.IO) { OcrAnalyzer.analyzeReceipt(context, uri) }
                        isAnalyzing = false
                        if (ocrRes != null) {
                            dateInput = ocrRes.dataStr
                            destInput = ocrRes.destinazione
                            amountInput = String.format(Locale.US, "%.2f", ocrRes.importo)

                            selectedCategory = when (ocrRes.categoriaSuggerita) {
                                "Bar/Rist/Alb" -> "🍴🛌🏻🍺"
                                "Parcheggio/Taxi" -> "🅿️🚕✈️🚅"
                                "Carburante" -> "⛽"
                                "Telepass" -> "🛣️"
                                "Nolo" -> "Nolo 🚗"
                                else -> "Altro"
                            }

                            selectedPayment = when (ocrRes.pagamentoSuggerito) {
                                "CC" -> "💳"
                                "Contanti" -> "💰"
                                "Carta Carburante" -> "💳⛽"
                                else -> "💳"
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

        if (selectedCategory != "🛣️") {
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
                Text("❌🇪🇺")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = noteInput,
            onValueChange = { noteInput = it },
            label = { Text("Note 📝") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                scope.launch {
                    val doubleAmount = amountInput.replace(",", ".").toDoubleOrNull() ?: 0.0
                    if (doubleAmount <= 0.0 && selectedCategory != "🛣️") {
                        statusMessage = "⚠️ Inserisci un importo valido!"
                        return@launch
                    }

                    isSaving = true

                    val catKey = when (selectedCategory) {
                        "🍴🛌🏻🍺" -> if (selectedPayment == "💳") "RISTORANTI_CC" else "RISTORANTI_CONTANTI"
                        "🅿️🚕✈️🚅" -> if (selectedPayment == "💳") "PARCHEGGI_CC" else "PARCHEGGI_CONTANTI"
                        "⛽" -> if (selectedPayment == "💳⛽") "CARBURANTE_CARTA" else "CARBURANTE_CC"
                        "🛣️" -> "TELEPASS"
                        "Nolo 🚗" -> "NOLO"
                        else -> "ALTRO"
                    }

                    val metodoStr = if (selectedCategory == "🛣️") null else when (selectedPayment) {
                        "💳" -> "CC (Carta)"
                        "💰" -> "Contanti"
                        else -> "Carta Carburante"
                    }

                    val success = withContext(Dispatchers.IO) {
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

                        val spesaInsert = SpesaInsert(
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

                        supabaseService.insertSpesa(spesaInsert)
                    }

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
            Text(if (isSaving) "Salvataggio..." else "💾 Salva Spesa")
        }

        if (statusMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(statusMessage, style = MaterialTheme.typography.bodyMedium)
        }
    }

    // Dialog for Photo Option: Camera Scatto vs. Album / Gallery / Drive
    if (showPhotoOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showPhotoOptionsDialog = false },
            title = { Text("Carica Foto 📷🖼️") },
            text = { Text("Scegli come inserire la foto dello scontrino:") },
            confirmButton = {
                Button(onClick = {
                    showPhotoOptionsDialog = false
                    val uri = createTempImageUri()
                    if (uri != null) {
                        cameraTempUri = uri
                        cameraLauncher.launch(uri)
                    }
                }) {
                    Text("📷 Scatta Foto")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showPhotoOptionsDialog = false
                    galleryPicker.launch("image/*")
                }) {
                    Text("🖼️ Scegli da Album / Drive")
                }
            }
        )
    }
}
