package com.expensepereport.app.util

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.expensepereport.app.data.GoogleDriveService
import com.expensepereport.app.data.SpesaRepository

class DatabaseBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val account = GoogleDriveService.getLastSignedInAccount(applicationContext)
            ?: return Result.failure()

        val repository = SpesaRepository(applicationContext)
        val dbFile = repository.getDatabaseFile()

        if (!dbFile.exists() || dbFile.length() == 0L) {
            return Result.failure()
        }

        val driveService = GoogleDriveService(applicationContext)
        val success = driveService.uploadDatabaseBackup(account, dbFile)

        return if (success) Result.success() else Result.retry()
    }
}
