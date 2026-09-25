package com.expensepereport.app.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.expensepereport.app.data.AppSettingsRepository
import com.expensepereport.app.data.SupabaseService
import com.expensepereport.app.util.DocumentGenerator
import com.expensepereport.app.util.lastExcelError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

@Composable
fun ExportScreen(
    supabaseService: SupabaseService,
    settingsRepository: AppSettingsRepository
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var yearInput by remember { mutableStateOf(2026) }
    var pdfMonth by remember { mutableStateOf(Calendar.getInstance().get(Calendar.MONTH) + 1) }

    var excelMode by remember { mutableStateOf("Mese Singolo") }
    var excelMonthStart by remember { mutableStateOf(Calendar.getInstance().get(Calendar.MONTH) + 1) }
    var excelMonthEnd by remember { mutableStateOf(Calendar.getInstance().get(Calendar.MONTH) + 1) }

    var isGeneratingExcel by remember { mutableStateOf(false) }
    var isGeneratingPdf by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }

    fun shareFile(file: File, mimeType: String) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Condividi Documento"))
        } catch (e: Exception) {
            statusMessage = "Errore durante la condivisione: ${e.message}"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("📁 Generazione & Esportazione Documenti", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = yearInput.toString(),
            onValueChange = { it.toIntOrNull()?.let { y -> yearInput = y } },
            label = { Text("Anno Esportazione") },
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        Text("📊 Genera & Condividi Excel", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        val hasTemplate = settingsRepository.hasCustomExcelTemplate()
        if (!hasTemplate) {
            Text("⚠️ Nessun modello Excel caricato nelle Impostazioni! Carica prima il file .xlsx nelle Impostazioni.", color = MaterialTheme.colorScheme.error)
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Mese Singolo", "Range Mesi", "Anno").forEach { mode ->
                    FilterChip(
                        selected = excelMode == mode,
                        onClick = { excelMode = mode },
                        label = { Text(mode) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (excelMode == "Mese Singolo" || excelMode == "Range Mesi") {
                Text("Mese Inizio: ${DocumentGenerator.MONTH_NAMES[excelMonthStart - 1]}")
                Slider(
                    value = excelMonthStart.toFloat(),
                    onValueChange = { excelMonthStart = it.toInt() },
                    valueRange = 1f..12f,
                    steps = 10
                )
            }

            if (excelMode == "Range Mesi") {
                Text("Mese Fine: ${DocumentGenerator.MONTH_NAMES[excelMonthEnd - 1]}")
                Slider(
                    value = excelMonthEnd.toFloat(),
                    onValueChange = { excelMonthEnd = it.toInt() },
                    valueRange = 1f..12f,
                    steps = 10
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    scope.launch {
                        val template = settingsRepository.getExcelTemplateFile()
                        if (template == null) {
                            statusMessage = "Modello Excel non trovato."
                            return@launch
                        }

                        isGeneratingExcel = true
                        val modeCode = when (excelMode) {
                            "Anno" -> "anno"
                            "Range Mesi" -> "range"
                            else -> "singolo"
                        }
                        val mStart = if (modeCode == "anno") 1 else excelMonthStart
                        val mEnd = if (modeCode == "anno") 12 else if (modeCode == "range") excelMonthEnd else excelMonthStart

                        val excelFile = withContext(Dispatchers.IO) {
                            DocumentGenerator.generateExcel(
                                context = context,
                                supabaseService = supabaseService,
                                templateFile = template,
                                year = yearInput,
                                mode = modeCode,
                                startMonth = mStart,
                                endMonth = mEnd
                            )
                        }
                        isGeneratingExcel = false

                        if (excelFile != null && excelFile.exists()) {
                            statusMessage = "✅ Excel generato! Apertura Share Sheet..."
                            shareFile(excelFile, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        } else {
                            val errDetail = lastExcelError ?: "Errore sconosciuto"
                            statusMessage = "❌ Errore durante la generazione dell'Excel: $errDetail"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isGeneratingExcel
            ) {
                Text(if (isGeneratingExcel) "Generazione Excel in corso..." else "📊 Genera & Condividi Excel")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        Text("🖼️📄 Genera & Condividi PDF Allegati (Grayscale)", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        Text("Mese PDF: ${DocumentGenerator.MONTH_NAMES[pdfMonth - 1]}")
        Slider(
            value = pdfMonth.toFloat(),
            onValueChange = { pdfMonth = it.toInt() },
            valueRange = 1f..12f,
            steps = 10
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                scope.launch {
                    isGeneratingPdf = true
                    val pdfFile = withContext(Dispatchers.IO) {
                        DocumentGenerator.generatePdfAttachments(
                            context = context,
                            supabaseService = supabaseService,
                            year = yearInput,
                            month = pdfMonth
                        )
                    }
                    isGeneratingPdf = false

                    if (pdfFile != null && pdfFile.exists()) {
                        statusMessage = "✅ PDF generato! Apertura Share Sheet..."
                        shareFile(pdfFile, "application/pdf")
                    } else {
                        statusMessage = "⚠️ Nessun allegato trovato o errore nella generazione del PDF per ${DocumentGenerator.MONTH_NAMES[pdfMonth - 1]} $yearInput."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isGeneratingPdf
        ) {
            Text(if (isGeneratingPdf) "Generazione PDF in corso..." else "📄 Genera & Condividi PDF Allegati")
        }

        if (statusMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(statusMessage, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
