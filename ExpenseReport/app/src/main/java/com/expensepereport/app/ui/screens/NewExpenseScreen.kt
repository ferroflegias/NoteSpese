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
import coil.compose.rememberAsyncImagePainter
import com.expensepereport.app.data.AttachmentRepository
import com.expensepereport.app.data.ExpenseCategories
import com.expensepereport.app.data.Spesa
import com.expensepereport.app.data.SpesaRepository
import com.expensepereport.app.ui.CropImageDialog
import com.expensepereport.app.util.OcrAnalyzer
import com.expensepereport.app.util.OcrException
import com.expensepereport.app.util.contentUriFor
import com.expensepereport.app.util.formatAmount
import com.expensepereport.app.util.isValidIsoDate
import com.expensepereport.app.util.parseAmount
import com.expensepereport.app.util.todayIsoDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    /** Photo being cropped: the crop dialog is open while it is not null. */
    var cropSourceUri by remember { mutableStateOf<Uri?>(null) }

    var isAnalyzing by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    var dateInput by remember { mutableStateOf(todayIsoDate()) }
    var destInput by remember { mutableStateOf("") }
    var scopoInput by remember { mutableStateOf("") }

    var selectedCategory by remember { mutableStateOf(ExpenseCategories.RISTORANTI) }
    var selectedPayment by remember { mutableStateOf(ExpenseCategories.PAYMENT_CC) }

    var amountInput by remember { mutableStateOf("") }
    var isForeignCurrency by remember { mutableStateOf(false) }
    var noteInput by remember { mutableStateOf("") }

    var statusMessage by remember { mutableStateOf("") }

    fun runOcr(uri: Uri, isPdf: Boolean) {
        scope.launch {
            isAnalyzing = true
            statusMessage = "🔍 Analisi OCR in corso..."
            val ocrRes = try {
                withContext(Dispatchers.IO) { OcrAnalyzer.analyzeReceipt(context, uri, isPdf) }
            } catch (e: OcrException) {
                statusMessage = "⚠️ OCR non riuscito: ${e.message}."
                return@launch
            } catch (e: Exception) {
                e.printStackTrace()
                statusMessage = "⚠️ OCR non riuscito: ${e.localizedMessage ?: e.javaClass.simpleName}."
                return@launch
            } finally {
                isAnalyzing = false
            }
            val source = if (isPdf) "nel PDF" else "nella foto"
            if (ocrRes != null) {
                dateInput = ocrRes.dataStr
                destInput = ocrRes.destinazione
                if (ocrRes.importo > 0) amountInput = formatAmount(ocrRes.importo)
                selectedCategory = ExpenseCategories.categoryFromOcr(ocrRes.categoriaSuggerita)
                selectedPayment = ExpenseCategories.paymentFromOcr(ocrRes.pagamentoSuggerito)
                noteInput = ocrRes.noteExtracted
                statusMessage = "✅ Dati estratti con successo tramite OCR ML Kit!"
            } else {
                statusMessage = "⚠️ Nessun testo rilevato $source."
            }
        }
    }

    fun startCropFlow(sourceUri: Uri) {
        cropSourceUri = sourceUri
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
            val tempFile = File.createTempFile(AttachmentRepository.CAMERA_FILE_PREFIX, ".jpg", context.cacheDir)
            context.contentUriFor(tempFile)
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
        ExpenseCategories.CATEGORY_OPTIONS.chunked(3).forEach { rowOptions ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowOptions.forEach { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = { Text(cat) }
                    )
                }
            }
        }

        if (selectedCategory != ExpenseCategories.TELEPASS) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Metodo Pagamento:", style = MaterialTheme.typography.bodyMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExpenseCategories.PAYMENT_OPTIONS.forEach { pay ->
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
                    if (doubleAmount <= 0.0 && selectedCategory != ExpenseCategories.TELEPASS) {
                        statusMessage = "⚠️ Inserisci un importo valido!"
                        return@launch
                    }

                    isSaving = true
                    try {
                        val currentUri = imageUri ?: pdfUri
                        val attachmentUrl = currentUri?.let {
                            attachmentRepository.saveAttachment(it, isPdf = pdfUri != null, dataSpesa = dateInput)
                        }

                        val spesa = Spesa(
                            data = dateInput,
                            destinazione = destInput.ifBlank { null },
                            scopo = scopoInput.ifBlank { null },
                            categoria = ExpenseCategories.categoryKey(selectedCategory, selectedPayment),
                            metodoPagamento = ExpenseCategories.paymentLabel(selectedCategory, selectedPayment),
                            importo = doubleAmount,
                            note = noteInput.ifBlank { null },
                            allegatoPath = attachmentUrl,
                            valutaStraniera = isForeignCurrency
                        )

                        spesaRepository.insertSpesa(spesa)
                        attachmentRepository.clearTemporaryPhotos()

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

    cropSourceUri?.let { sourceUri ->
        CropImageDialog(
            sourceUri = sourceUri,
            onCropSuccess = { croppedUri ->
                imageUri = croppedUri
                pdfUri = null
                cropSourceUri = null
            },
            onDismiss = {
                // Without cropping, a new photo is used as it is (re-cropping the current one changes nothing)
                if (sourceUri != imageUri) {
                    imageUri = sourceUri
                    pdfUri = null
                }
                cropSourceUri = null
            }
        )
    }
}
