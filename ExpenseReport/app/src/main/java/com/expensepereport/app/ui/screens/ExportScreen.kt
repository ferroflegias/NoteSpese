package com.expensepereport.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.expensepereport.app.data.AppSettingsRepository
import com.expensepereport.app.data.AttachmentRepository
import com.expensepereport.app.data.SpesaRepository
import com.expensepereport.app.ui.components.MonthSelector
import com.expensepereport.app.ui.components.YearSelector
import com.expensepereport.app.util.DocumentGenerator
import com.expensepereport.app.util.ExcelExportMode
import com.expensepereport.app.util.contentUriFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

private val EXCEL_MODE_LABELS = listOf(
    ExcelExportMode.SINGLE_MONTH to "Mese Singolo",
    ExcelExportMode.MONTH_RANGE to "Range Mesi",
    ExcelExportMode.FULL_YEAR to "Anno"
)

@Composable
fun ExportScreen(
    spesaRepository: SpesaRepository,
    settingsRepository: AppSettingsRepository,
    attachmentRepository: AttachmentRepository
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val currentMonth = remember { Calendar.getInstance().get(Calendar.MONTH) + 1 }
    var yearInput by remember { mutableStateOf(Calendar.getInstance().get(Calendar.YEAR)) }
    var pdfMonth by remember { mutableStateOf(currentMonth) }

    var excelMode by remember { mutableStateOf(ExcelExportMode.SINGLE_MONTH) }
    var excelMonthStart by remember { mutableStateOf(currentMonth) }
    var excelMonthEnd by remember { mutableStateOf(currentMonth) }

    var isGeneratingExcel by remember { mutableStateOf(false) }
    var isGeneratingPdf by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }

    fun shareFile(file: File, mimeType: String) {
        try {
            val uri = context.contentUriFor(file)
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

        YearSelector(year = yearInput, onYearSelected = { yearInput = it })

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        Text("📊 Genera & Condividi Excel", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        val hasTemplate = settingsRepository.hasCustomExcelTemplate()
        if (!hasTemplate) {
            Text("⚠️ Nessun modello Excel caricato nelle Impostazioni! Carica prima il file .xlsx nelle Impostazioni.", color = MaterialTheme.colorScheme.error)
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                EXCEL_MODE_LABELS.forEach { (mode, modeLabel) ->
                    FilterChip(
                        selected = excelMode == mode,
                        onClick = { excelMode = mode },
                        label = { Text(modeLabel) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (excelMode != ExcelExportMode.FULL_YEAR) {
                MonthSelector(
                    label = if (excelMode == ExcelExportMode.MONTH_RANGE) "Mese Inizio" else "Mese",
                    month = excelMonthStart,
                    onMonthSelected = { excelMonthStart = it }
                )
            }

            if (excelMode == ExcelExportMode.MONTH_RANGE) {
                Spacer(modifier = Modifier.height(8.dp))
                MonthSelector(
                    label = "Mese Fine",
                    month = excelMonthEnd,
                    onMonthSelected = { excelMonthEnd = it }
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
                        // A range selected "backwards" (end before start) would export nothing
                        val (mStart, mEnd) = when (excelMode) {
                            ExcelExportMode.FULL_YEAR -> 1 to 12
                            ExcelExportMode.MONTH_RANGE -> minOf(excelMonthStart, excelMonthEnd) to maxOf(excelMonthStart, excelMonthEnd)
                            ExcelExportMode.SINGLE_MONTH -> excelMonthStart to excelMonthStart
                        }

                        val result = withContext(Dispatchers.IO) {
                            DocumentGenerator.generateExcel(
                                context = context,
                                spesaRepository = spesaRepository,
                                templateFile = template,
                                year = yearInput,
                                mode = excelMode,
                                startMonth = mStart,
                                endMonth = mEnd
                            )
                        }
                        isGeneratingExcel = false

                        result
                            .onSuccess { excelFile ->
                                statusMessage = "✅ Excel generato! Apertura Share Sheet..."
                                shareFile(excelFile, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                            }
                            .onFailure { e ->
                                statusMessage = "❌ Errore durante la generazione dell'Excel: ${e.javaClass.simpleName}: ${e.message}"
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

        MonthSelector(
            label = "Mese",
            month = pdfMonth,
            onMonthSelected = { pdfMonth = it }
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                scope.launch {
                    isGeneratingPdf = true
                    val pdfFile = withContext(Dispatchers.IO) {
                        DocumentGenerator.generatePdfAttachments(
                            context = context,
                            spesaRepository = spesaRepository,
                            year = yearInput,
                            month = pdfMonth,
                            readAttachment = attachmentRepository::readAttachmentBytes
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
