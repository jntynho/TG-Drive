package app.tgdrive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.tgdrive.data.DriveFile
import kotlinx.coroutines.*

/** One user-initiated transfer at a time. No background scanning or storage-wide permission. */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val drive get() = (application as DriveApp).drive

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "File transfers", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive == true) return START_NOT_STICKY
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_drive).setContentTitle("TG Drive").setContentText(text)
            .setContentIntent(pending).setOngoing(true).setOnlyAlertOnce(true).build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, 10, notification("Preparing transfer…"), type)
        job = scope.launch {
            val observer = launch {
                drive.state.collect { state ->
                    getSystemService(NotificationManager::class.java).notify(10, notification(state.transfer ?: "Preparing transfer…"))
                }
            }
            try {
                val uri = intent?.getStringExtra("uri")?.let(Uri::parse) ?: error("No file selected.")
                when (intent.action) {
                    UPLOAD -> drive.upload(uri, intent.getStringExtra("folder") ?: "")
                    DOWNLOAD -> {
                        val id = intent.getLongExtra("messageId", 0)
                        val file: DriveFile = drive.state.value.files.firstOrNull { it.messageId == id } ?: error("File is not in the index. Refresh and retry.")
                        drive.download(file, uri)
                    }
                    else -> error("Unknown transfer action.")
                }
            } catch (e: Exception) {
                if (e !is CancellationException) drive.fail(e.message ?: "Transfer failed. Please retry.")
            } finally {
                observer.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // Android 15+ can time out a dataSync foreground service.
    override fun onTimeout(startId: Int, fgsType: Int) {
        drive.fail("Android stopped this long-running transfer. Reopen the app and check its status before retrying.")
        job?.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val UPLOAD = "app.tgdrive.UPLOAD"
        const val DOWNLOAD = "app.tgdrive.DOWNLOAD"
        private const val CHANNEL = "transfers"
    }
}
