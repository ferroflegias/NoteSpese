package com.expensepereport.app.util

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.expensepereport.app.data.AppSettingsRepository
import com.expensepereport.app.data.DropboxService
import com.expensepereport.app.data.SpesaRepository

class DatabaseBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dropboxService = DropboxService(AppSettingsRepository(applicationContext))
        if (!dropboxService.isConnected()) return Result.failure()

        val dbFile = SpesaRepository(applicationContext).getDatabaseFileForBackup()
        if (!dbFile.exists() || dbFile.length() == 0L) {
            return Result.failure()
        }

        val success = dropboxService.uploadDatabaseBackup(dbFile)
        return if (success) Result.success() else Result.retry()
    }
}
