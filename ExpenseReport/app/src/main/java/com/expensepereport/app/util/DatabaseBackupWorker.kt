package com.expensepereport.app.util

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.expensepereport.app.data.AppSettingsRepository
import com.expensepereport.app.data.GoogleDriveService
import com.expensepereport.app.data.SpesaRepository
import kotlinx.coroutines.flow.first

class DatabaseBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settingsRepo = AppSettingsRepository(applicationContext)
        val savedEmail = settingsRepo.googleAccountEmailFlow.first()

        val accountEmail = if (savedEmail.isNotBlank()) {
            savedEmail
        } else {
            GoogleDriveService.getLastSignedInAccount(applicationContext)?.email ?: return Result.failure()
        }

        val repository = SpesaRepository(applicationContext)
        val dbFile = repository.getDatabaseFile()

        if (!dbFile.exists() || dbFile.length() == 0L) {
            return Result.failure()
        }

        val driveService = GoogleDriveService(applicationContext)
        val success = driveService.uploadDatabaseBackup(accountEmail, dbFile)

        return if (success) Result.success() else Result.retry()
    }
}
