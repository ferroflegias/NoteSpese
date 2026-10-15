package com.expensepereport.app.util

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.expensepereport.app.data.GoogleDriveService
import com.expensepereport.app.data.SpesaRepository
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.tasks.Tasks

class DatabaseBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val lastAccount = GoogleDriveService.getLastSignedInAccount(applicationContext)
            ?: return Result.failure()

        // Refresh Google Sign-In token silently if needed for background execution
        val account = try {
            val client = GoogleSignIn.getClient(applicationContext, GoogleDriveService.getGoogleSignInOptions())
            Tasks.await(client.silentSignIn()) ?: lastAccount
        } catch (e: Exception) {
            lastAccount
        }

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
