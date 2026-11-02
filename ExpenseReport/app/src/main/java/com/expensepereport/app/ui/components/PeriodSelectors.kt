package com.expensepereport.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.expensepereport.app.util.DocumentGenerator

/**
 * Month picker as a drop-down list ([month] is 1-12). It replaces a Slider whose float values
 * (e.g. 9.9999995) were truncated with toInt(), which made October impossible to select.
 */
@Composable
fun MonthSelector(
    month: Int,
    onMonthSelected: (Int) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    label: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            val name = DocumentGenerator.MONTH_NAMES[month - 1]
            Text(if (label != null) "$label: $name  ▾" else "$name  ▾")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DocumentGenerator.MONTH_NAMES.forEachIndexed { index, name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onMonthSelected(index + 1)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * Year picker with previous/next buttons. It replaces a text field that could not be cleared
 * (an empty value was rejected) and accepted partial years such as "202" while typing.
 */
@Composable
fun YearSelector(
    year: Int,
    onYearSelected: (Int) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        OutlinedButton(onClick = { onYearSelected(year - 1) }) { Text("◀") }
        Text("Anno $year", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { onYearSelected(year + 1) }) { Text("▶") }
    }
}
