package com.expensepereport.app.data.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.expensepereport.app.data.Spesa

@Entity(tableName = "spese")
data class SpesaEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val data: String,
    val destinazione: String? = null,
    val scopo: String? = null,
    val categoria: String,
    @ColumnInfo(name = "metodo_pagamento")
    val metodoPagamento: String? = null,
    val importo: Double = 0.0,
    val km: Double = 0.0,
    val note: String? = null,
    @ColumnInfo(name = "allegato_path")
    val allegatoPath: String? = null,
    @ColumnInfo(name = "valuta_straniera")
    val valutaStraniera: Int = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: String? = null
) {
    fun toSpesa(): Spesa {
        return Spesa(
            id = id,
            data = data,
            destinazione = destinazione,
            scopo = scopo,
            categoria = categoria,
            metodoPagamento = metodoPagamento,
            importo = importo,
            km = km,
            note = note,
            allegatoPath = allegatoPath,
            valutaStraniera = valutaStraniera,
            createdAt = createdAt
        )
    }

    companion object {
        fun fromSpesa(spesa: Spesa): SpesaEntity {
            return SpesaEntity(
                id = spesa.id ?: 0,
                data = spesa.data,
                destinazione = spesa.destinazione,
                scopo = spesa.scopo,
                categoria = spesa.categoria,
                metodoPagamento = spesa.metodoPagamento,
                importo = spesa.importo,
                km = spesa.km,
                note = spesa.note,
                allegatoPath = spesa.allegatoPath,
                valutaStraniera = spesa.valutaStraniera,
                createdAt = spesa.createdAt
            )
        }
    }
}
