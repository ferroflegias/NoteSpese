package com.expensepereport.app.data

/** An expense as used by the UI and the document generators. */
data class Spesa(
    /** Null for an expense not yet saved. */
    val id: Long? = null,
    val data: String, // YYYY-MM-DD
    val destinazione: String? = null,
    val scopo: String? = null,
    val categoria: String,
    val metodoPagamento: String? = null,
    val importo: Double = 0.0,
    val km: Double = 0.0,
    val note: String? = null,
    val allegatoPath: String? = null,
    val valutaStraniera: Boolean = false,
    val createdAt: String? = null
)

/** Year and month folders ("AAAA", "MM") used to organise the attachments of an expense date. */
fun attachmentFolderParts(dataSpesa: String): Pair<String, String> {
    val year = if (dataSpesa.length >= 4) dataSpesa.substring(0, 4) else "0000"
    val month = if (dataSpesa.length >= 7) dataSpesa.substring(5, 7) else "00"
    return year to month
}
