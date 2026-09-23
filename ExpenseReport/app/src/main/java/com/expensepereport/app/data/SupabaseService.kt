package com.expensepereport.app.data

import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SupabaseService(
    private val baseUrl: String,
    private val anonKey: String
) {
    private val jsonInstance = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(jsonInstance)
        }
    }

    private val bucketName = "allegati-spese"

    private fun cleanBaseUrl(): String {
        return baseUrl.trim().trimEnd('/')
    }

    // --- REST SQL DATABASE API (POSTGREST) ---

    suspend fun getSpese(): List<Spesa> {
        val cleanUrl = cleanBaseUrl()
        val url = "$cleanUrl/rest/v1/spese?select=*&order=data.desc,id.desc"
        val response = client.get(url) {
            header("apikey", anonKey)
            header("Authorization", "Bearer $anonKey")
            header("Accept", "application/json")
        }
        val responseText = response.bodyAsText()
        return jsonInstance.decodeFromString<List<Spesa>>(responseText)
    }

    suspend fun getSpeseForMonth(year: Int, month: Int): List<Spesa> {
        val cleanUrl = cleanBaseUrl()
        val startDate = String.format(Locale.US, "%04d-%02d-01", year, month)
        val endDate = if (month == 12) {
            String.format(Locale.US, "%04d-01-01", year + 1)
        } else {
            String.format(Locale.US, "%04d-%02d-01", year, month + 1)
        }

        val url = "$cleanUrl/rest/v1/spese?data=gte.$startDate&data=lt.$endDate&order=data.asc,id.asc"
        val response = client.get(url) {
            header("apikey", anonKey)
            header("Authorization", "Bearer $anonKey")
            header("Accept", "application/json")
        }
        val responseText = response.bodyAsText()
        return jsonInstance.decodeFromString<List<Spesa>>(responseText)
    }

    suspend fun insertSpesa(spesa: Spesa): Boolean {
        val cleanUrl = cleanBaseUrl()
        val url = "$cleanUrl/rest/v1/spese"
        val jsonBody = jsonInstance.encodeToString(spesa)
        val response = client.post(url) {
            header("apikey", anonKey)
            header("Authorization", "Bearer $anonKey")
            contentType(ContentType.Application.Json)
            header("Prefer", "return=minimal")
            setBody(jsonBody)
        }
        return response.status.isSuccess()
    }

    suspend fun updateSpesa(id: Long, spesa: Spesa): Boolean {
        val cleanUrl = cleanBaseUrl()
        val url = "$cleanUrl/rest/v1/spese?id=eq.$id"
        val jsonBody = jsonInstance.encodeToString(spesa)
        val response = client.patch(url) {
            header("apikey", anonKey)
            header("Authorization", "Bearer $anonKey")
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
        }
        return response.status.isSuccess()
    }

    suspend fun deleteSpesa(id: Long): Boolean {
        val cleanUrl = cleanBaseUrl()
        val url = "$cleanUrl/rest/v1/spese?id=eq.$id"
        val response = client.delete(url) {
            header("apikey", anonKey)
            header("Authorization", "Bearer $anonKey")
        }
        return response.status.isSuccess()
    }

    // --- SUPABASE STORAGE API ---

    suspend fun uploadAttachment(
        fileBytes: ByteArray,
        dataSpesa: String,
        extension: String,
        contentType: String,
        userId: String = "default_user"
    ): String? {
        val cleanUrl = cleanBaseUrl()
        val annoMese = if (dataSpesa.length >= 7) dataSpesa.substring(0, 7) else "2026-01"
        val nowStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "rec_${dataSpesa}_${nowStr}.${extension}"
        val storagePath = "$userId/$annoMese/$filename"

        val uploadUrl = "$cleanUrl/storage/v1/object/$bucketName/$storagePath"

        val response = client.post(uploadUrl) {
            header("apikey", anonKey)
            header("Authorization", "Bearer $anonKey")
            contentType(ContentType.parse(contentType))
            setBody(fileBytes)
        }

        return if (response.status.isSuccess()) {
            "$cleanUrl/storage/v1/object/public/$bucketName/$storagePath"
        } else {
            null
        }
    }

    suspend fun deleteAttachment(publicUrl: String): Boolean {
        if (publicUrl.isBlank()) return false
        val cleanUrl = cleanBaseUrl()
        val token = "$bucketName/"
        if (!publicUrl.contains(token)) return false

        val storagePath = publicUrl.substringAfter(token)
        val url = "$cleanUrl/storage/v1/object/$bucketName/$storagePath"

        val response = client.delete(url) {
            header("apikey", anonKey)
            header("Authorization", "Bearer $anonKey")
        }
        return response.status.isSuccess()
    }

    suspend fun downloadBytes(url: String): ByteArray? {
        return try {
            val response = client.get(url) {
                header("apikey", anonKey)
                header("Authorization", "Bearer $anonKey")
            }
            response.readBytes()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
