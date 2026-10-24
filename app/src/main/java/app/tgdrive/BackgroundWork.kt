package app.tgdrive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class LibraryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val profile = inputData.getString("profile") ?: return Result.failure()
        val bundle = withContext(Dispatchers.Main) { (applicationContext as DriveApp).sessions.bundle(profile) }
        return try {
            bundle.library.awaitReady()
            withTimeout(8 * 60 * 1000L) {
                if (inputData.getBoolean("scan", false)) { bundle.backups.scan(); bundle.backups.purgeExpiredTrash() }
                val pending = bundle.library.state.value.tasks.any { it.state in setOf(app.tgdrive.library.TaskState.QUEUED, app.tgdrive.library.TaskState.RETRY_WAIT) }
                if (pending) setForeground(foregroundInfo(applicationContext))
                bundle.transfers.drain(7 * 60 * 1000L)
            }
            notifyFailures(bundle)
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { bundle.library.report(e); if (runAttemptCount < 2) Result.retry() else Result.failure() }
    }
    private fun notifyFailures(bundle: SessionBundle) {
        val state = bundle.library.state.value
        if (!state.preferences.backupNotifications || state.rules.isEmpty()) return
        val failed = state.tasks.count { it.state == app.tgdrive.library.TaskState.FAILED }
        if (failed == 0 && state.rules.none { it.error.isNotEmpty() }) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("backup-health", "Backup health", NotificationManager.IMPORTANCE_DEFAULT))
        val pending = PendingIntent.getActivity(applicationContext, 20, Intent(applicationContext,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext,"backup-health").setSmallIcon(R.drawable.ic_drive).setContentTitle("TG Drive backup needs attention")
            .setContentText("Open the app to inspect incomplete work. No files were automatically marked safe.").setContentIntent(pending).setAutoCancel(true).build()
        runCatching { manager.notify(20,notification) }
    }
    companion object {
        fun foregroundInfo(context: Context): ForegroundInfo {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("transfers", "File transfers", NotificationManager.IMPORTANCE_LOW))
            val notification = NotificationCompat.Builder(context,"transfers").setSmallIcon(R.drawable.ic_drive).setContentTitle("TG Drive")
                .setContentText("Processing saved transfers").setOngoing(true).build()
            return if(android.os.Build.VERSION.SDK_INT>=29)ForegroundInfo(10,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(10,notification)
        }
    }
}

object BackgroundWork {
    fun enqueue(context: Context, profile: String) {
        val request = OneTimeWorkRequestBuilder<LibraryWorker>().setInitialDelay(60,TimeUnit.SECONDS).setInputData(workDataOf("profile" to profile))
            // Evaluate the current preferences in the engine; queued work must not retain obsolete rules.
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("library-$profile",ExistingWorkPolicy.APPEND_OR_REPLACE,request)
    }
    fun schedule(context: Context, profile: String, hours: Int) {
        val request = PeriodicWorkRequestBuilder<LibraryWorker>(hours.coerceIn(1,24).toLong(),TimeUnit.HOURS)
            .setInputData(workDataOf("profile" to profile,"scan" to true))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("backup-$profile",ExistingPeriodicWorkPolicy.UPDATE,request)
    }
}
