package com.expensepereport.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.rememberAsyncImagePainter
import com.expensepereport.app.data.AttachmentRepository
import com.expensepereport.app.data.SpesaInsert
import com.expensepereport.app.data.SpesaRepository
import com.expensepereport.app.ui.CropImageDialog
import com.expensepereport.app.util.OcrAnalyzer
import com.expensepereport.app.util.formatAmount
import com.expensepereport.app.util.isValidIsoDate
import com.expensepereport.app.util.parseAmount
import com.expensepereport.app.util.todayIsoDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val CATEGORY_RISTORANTI = "🍴🛌🏻🍺"
private const val CATEGORY_PARCHEGGI = "🅿️🚕✈️🚅"
private const val CATEGORY_CARBURANTE = "⛽"
private const val CATEGORY_TELEPASS = "🛣️"
private const val CATEGORY_NOLO = "Nolo 🚗"
private const val CATEGORY_ALTRO = "Altro"

private const val PAYMENT_CC = "💳"
private const val PAYMENT_CASH = "💰"
private const val PAYMENT_FUEL_CARD = "💳⛽"

private val CATEGORY_OPTIONS = listOf(
    CATEGORY_RISTORANTI, CATEGORY_PARCHEGGI, CATEGORY_CARBURANTE,
    CATEGORY_TELEPASS, CATEGORY_NOLO, CATEGORY_ALTRO
)
private val PAYMENT_OPTIONS = listOf(PAYMENT_CC, PAYMENT_CASH, PAYMENT_FUEL_CARD)

/** Maps the category suggested by the OCR to the chip shown in the UI. */
private fun categoryFromOcr(suggestion: String): String = when (suggestion) {
    "Bar/Rist/Alb" -> CATEGORY_RISTORANTI
    "Parcheggio/Taxi" -> CATEGORY_PARCHEGGI
    "Carburante" -> CATEGORY_CARBURANTE
    "Telepass" -> CATEGORY_TELEPASS
    "Nolo" -> CATEGORY_NOLO
    else -> CATEGORY_ALTRO
}

private fun paymentFromOcr(suggestion: String): String = when (suggestion) {
    "Contanti" -> PAYMENT_CASH
    "Carta Carburante" -> PAYMENT_FUEL_CARD
    else -> PAYMENT_CC
}

/** Database category key, matching the columns of the Excel template. */
private fun categoryKey(category: String, payment: String): String = when (category) {
    CATEGORY_RISTORANTI -> if (payment == PAYMENT_CC) "RISTORANTI_CC" else "RISTORANTI_CONTANTI"
    CATEGORY_PARCHEGGI -> if (payment == PAYMENT_CC) "PARCHEGGI_CC" else "PARCHEGGI_CONTANTI"
    CATEGORY_CARBURANTE -> if (payment == PAYMENT_FUEL_CARD) "CARBURANTE_CARTA" else "CARBURANTE_CC"
    CATEGORY_TELEPASS -> "TELEPASS"
    CATEGORY_NOLO -> "NOLO"
    else -> "ALTRO"
}

private fun paymentLabel(category: String, payment: String): String? =
    if (category == CATEGORY_TELEPASS) null else when (payment) {
        PAYMENT_CC -> "CC (Carta)"
        PAYMENT_CASH -> "Contanti"
        else -> "Carta Carburante"
    }

@Composable
fun NewExpenseScreen(
    spesaRepository: SpesaRepository,
    attachmentRepository: AttachmentRepository
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var pdfUri by remember { mutableStateOf<Uri?>(null) }

    var cameraTempUri by remember { mutableStateOf<Uri?>(null) }
    var showPhotoOptionsDialog by remember { mutableStateOf(false) }

    var cropSourceUri by remember { mutableStateOf<Uri?>(null) }
    var showCropDialog by remember { mutableStateOf(false) }

    var isAnalyzing by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    var dateInput by remember { mutableStateOf(todayIsoDate()) }
    var destInput by remember { mutableStateOf("") }
    var scopoInput by remember { mutableStateOf("") }

    var selectedCategory by remember { mutableStateOf(CATEGORY_RISTORANTI) }
    var selectedPayment by remember { mutableStateOf(PAYMENT_CC) }

    var amountInput by remember { mutableStateOf("") }
    var isForeignCurrency by remember { mutableStateOf(false) }
    var noteInput by remember { mutableStateOf("") }

    var statusMessage by remember { mutableStateOf("") }

    fun runOcr(uri: Uri, isPdf: Boolean) {
        scope.launch {
            isAnalyzing = true
            val ocrRes = try {
                withContext(Dispatchers.IO) { OcrAnalyzer.analyzeReceipt(context, uri, isPdf) }
            } finally {
                isAnalyzing = false
            }
            val source = if (isPdf) "nel PDF" else "nella foto"
            if (ocrRes != null) {
                dateInput = ocrRes.dataStr
                destInput = ocrRes.destinazione
                if (ocrRes.importo > 0) amountInput = formatAmount(ocrRes.importo)
                selectedCategory = categoryFromOcr(ocrRes.categoriaSuggerita)
                selectedPayment = paymentFromOcr(ocrRes.pagamentoSuggerito)
                noteInput = ocrRes.noteExtracted
                statusMessage = "✅ Dati estratti con successo tramite OCR ML Kit!"
            } else {
                statusMessage = "⚠️ Nessun testo rilevato $source."
            }
        }
    }

    fun startCropFlow(sourceUri: Uri) {
        cropSourceUri = sourceUri
        showCropDialog = true
    }

    val galleryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            startCropFlow(uri)
        }
    }

    fun createTempImageUri(): Uri? {
        return try {
            val tempFile = File.createTempFile("camera_photo_", ".jpg", context.cacheDir)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempFile)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        val uri = cameraTempUri
        if (success && uri != null) {
            startCropFlow(uri)
        }
    }

    fun launchCamera() {
        try {
            val uri = createTempImageUri()
            if (uri != null) {
                cameraTempUri = uri
                cameraLauncher.launch(uri)
            } else {
                statusMessage = "⚠️ Impossibile creare il file temporaneo per la fotocamera."
            }
        } catch (e: Exception) {
            e.printStackTrace()
            statusMessage = "⚠️ Errore nell'apertura della fotocamera: ${e.localizedMessage ?: e.message}"
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            launchCamera()
        } else {
            statusMessage = "⚠️ Permesso Fotocamera necessario per scattare una foto."
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
            OutlinedButton(
                onClick = { startCropFlow(uri) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("✂️ Ritaglia Foto")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { runOcr(uri, isPdf = false) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isAnalyzing
            ) {
                Text(if (isAnalyzing) "Analisi OCR in corso..." else "🔍 Estrai Testo con OCR Locale ML Kit")
            }
        }

        pdfUri?.let { uri ->
            Spacer(modifier = Modifier.height(8.dp))
            Text("📄 PDF Selezionato: $uri", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { runOcr(uri, isPdf = true) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isAnalyzing
            ) {
                Text(if (isAnalyzing) "Analisi OCR in corso..." else "🔍 Estrai Testo con OCR Locale ML Kit")
            }
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
            CATEGORY_OPTIONS.take(3).forEach { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = cat },
                    label = { Text(cat) }
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CATEGORY_OPTIONS.drop(3).forEach { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = cat },
                    label = { Text(cat) }
                )
            }
        }

        if (selectedCategory != CATEGORY_TELEPASS) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Metodo Pagamento:", style = MaterialTheme.typography.bodyMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PAYMENT_OPTIONS.forEach { pay ->
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
                    if (!isValidIsoDate(dateInput)) {
                        statusMessage = "⚠️ Data non valida! Usa il formato AAAA-MM-GG."
                        return@launch
                    }
                    val doubleAmount = parseAmount(amountInput) ?: 0.0
                    if (doubleAmount <= 0.0 && selectedCategory != CATEGORY_TELEPASS) {
                        statusMessage = "⚠️ Inserisci un importo valido!"
                        return@launch
                    }

                    isSaving = true
                    try {
                        val currentUri = imageUri ?: pdfUri
                        val attachmentUrl = currentUri?.let {
                            attachmentRepository.saveAttachment(it, isPdf = pdfUri != null, dataSpesa = dateInput)
                        }

                        val spesaInsert = SpesaInsert(
                            data = dateInput,
                            destinazione = destInput.ifBlank { null },
                            scopo = scopoInput.ifBlank { null },
                            categoria = categoryKey(selectedCategory, selectedPayment),
                            metodoPagamento = paymentLabel(selectedCategory, selectedPayment),
                            importo = doubleAmount,
                            note = noteInput.ifBlank { null },
                            allegatoPath = attachmentUrl,
                            valutaStraniera = if (isForeignCurrency) 1 else 0
                        )

                        spesaRepository.insertSpesa(spesaInsert)

                        statusMessage = if (currentUri != null && attachmentUrl == null) {
                            "⚠️ Spesa registrata, ma non è stato possibile salvare l'allegato."
                        } else {
                            "🎉 Spesa registrata con successo nel database!"
                        }
                        destInput = ""
                        scopoInput = ""
                        amountInput = ""
                        noteInput = ""
                        isForeignCurrency = false
                        imageUri = null
                        pdfUri = null
                    } catch (e: Exception) {
                        e.printStackTrace()
                        statusMessage = "❌ Errore durante il salvataggio: ${e.localizedMessage ?: e.message}"
                    } finally {
                        isSaving = false
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

    if (showPhotoOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showPhotoOptionsDialog = false },
            title = { Text("Carica Foto 📷🖼️") },
            text = { Text("Scegli come inserire la foto dello scontrino:") },
            confirmButton = {
                Button(onClick = {
                    showPhotoOptionsDialog = false
                    val hasPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED

                    if (hasPermission) {
                        launchCamera()
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
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

    if (showCropDialog && cropSourceUri != null) {
        CropImageDialog(
            sourceUri = cropSourceUri!!,
            onCropSuccess = { croppedUri ->
                imageUri = croppedUri
                pdfUri = null
                showCropDialog = false
            },
            onDismiss = {
                if (imageUri == null) {
                    imageUri = cropSourceUri
                    pdfUri = null
                }
                showCropDialog = false
            }
        )
    }
}
