package com.expensepereport.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.expensepereport.app.data.Spesa
import com.expensepereport.app.data.SupabaseService
import com.expensepereport.app.util.DocumentGenerator
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

@Composable
fun ReportsScreen(supabaseService: SupabaseService) {
    val scope = rememberCoroutineScope()

    var selectedYear by remember { mutableStateOf(2026) }
    var selectedMonth by remember { mutableStateOf(Calendar.getInstance().get(Calendar.MONTH) + 1) }

    var monthSpese by remember { mutableStateOf<List<Spesa>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    fun loadMonthData() {
        scope.launch {
            isLoading = true
            try {
                monthSpese = supabaseService.getSpeseForMonth(selectedYear, selectedMonth)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isLoading = false
        }
    }

    LaunchedEffect(selectedYear, selectedMonth) {
        loadMonthData()
    }

    val totaleMese = monthSpese.sumOf { it.importo }
    val totaleCC = monthSpese.filter { it.metodoPagamento == "CC (Carta)" || it.metodoPagamento == "CC" }.sumOf { it.importo }
    val totaleCash = monthSpese.filter { it.metodoPagamento == "Contanti" }.sumOf { it.importo }
    val totaleCartaCarburante = monthSpese.filter { it.metodoPagamento == "Carta Carburante" }.sumOf { it.importo }
    val totaleTelepass = monthSpese.filter { it.categoria == "TELEPASS" }.sumOf { it.importo }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("📊 Riepilogo & Report Spese", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = selectedYear.toString(),
                onValueChange = { it.toIntOrNull()?.let { y -> selectedYear = y } },
                label = { Text("Anno") },
                modifier = Modifier.weight(1f)
            )

            var expandedMonth by remember { mutableStateOf(false) }
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { expandedMonth = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(DocumentGenerator.MONTH_NAMES[selectedMonth - 1])
                }
                DropdownMenu(
                    expanded = expandedMonth,
                    onDismissRequest = { expandedMonth = false }
                ) {
                    DocumentGenerator.MONTH_NAMES.forEachIndexed { index, name ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                selectedMonth = index + 1
                                expandedMonth = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Totale Generale Mese:", style = MaterialTheme.typography.titleMedium)
                    Text("€ ${String.format(Locale.US, "%.2f", totaleMese)}", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    Text("1) Totale CC (💳 Carta): € ${String.format(Locale.US, "%.2f", totaleCC)}", style = MaterialTheme.typography.bodyLarge)
                    Text("2) Totale Cash (💰 Contanti): € ${String.format(Locale.US, "%.2f", totaleCash)}", style = MaterialTheme.typography.bodyLarge)
                    Text("3) Totale Carta Carburante (💳⛽): € ${String.format(Locale.US, "%.2f", totaleCartaCarburante)}", style = MaterialTheme.typography.bodyLarge)
                    Text("4) Totale Telepass (🛣️): € ${String.format(Locale.US, "%.2f", totaleTelepass)}", style = MaterialTheme.typography.bodyLarge)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("Breakdown per Categoria", style = MaterialTheme.typography.titleMedium)
            val categoryGroup = monthSpese.groupBy { it.categoria }
            categoryGroup.forEach { (cat, list) ->
                val sum = list.sumOf { it.importo }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(cat)
                    Text("€ ${String.format(Locale.US, "%.2f", sum)} (${list.size})")
                }
            }
        }
    }
}
