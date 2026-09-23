package com.expensepereport.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Spesa(
    val id: Long? = null,
    val data: String, // YYYY-MM-DD
    val destinazione: String? = null,
    val scopo: String? = null,
    val categoria: String,
    @SerialName("metodo_pagamento")
    val metodoPagamento: String? = null,
    val importo: Double = 0.0,
    val km: Double = 0.0,
    val note: String? = null,
    @SerialName("allegato_path")
    val allegatoPath: String? = null,
    @SerialName("valuta_straniera")
    val valutaStraniera: Int = 0,
    @SerialName("created_at")
    val createdAt: String? = null
)
