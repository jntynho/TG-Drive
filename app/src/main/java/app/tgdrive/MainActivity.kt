package app.tgdrive

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tgdrive.data.DriveFile
import app.tgdrive.ui.DriveScreen
import app.tgdrive.ui.DriveTheme

class MainActivity : ComponentActivity() {
    private val drive get() = (application as DriveApp).drive
    private var pendingDownloadId = 0L
    private var uploadFolder = ""
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val upload = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            transfer(Intent(this, TransferService::class.java).setAction(TransferService.UPLOAD).putExtra("uri", uri.toString()).putExtra("folder", uploadFolder))
        }
    }
    private val download = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = drive.state.value.files.firstOrNull { it.messageId == pendingDownloadId }
        if (uri != null && file != null) {
            transfer(Intent(this, TransferService::class.java).setAction(TransferService.DOWNLOAD).putExtra("uri", uri.toString()).putExtra("messageId", file.messageId))
        }
        if (uri != null && file == null) drive.fail("File selection was interrupted. Wait for sign-in, refresh and choose Save again.")
        pendingDownloadId = 0L
    }
    private fun transfer(intent: Intent) {
        if (drive.state.value.transfer != null) { drive.fail("Wait for the current transfer to finish."); return }
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        runCatching { ContextCompat.startForegroundService(this, intent) }
            .onFailure { drive.fail("Android could not start the transfer. Keep the app open and retry.") }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uploadFolder = savedInstanceState?.getString("uploadFolder") ?: ""
        pendingDownloadId = savedInstanceState?.getLong("downloadId", 0) ?: 0
        enableEdgeToEdge()
        setContent {
            val state by drive.state.collectAsStateWithLifecycle()
            var folder by rememberSaveable { mutableStateOf("") }
            DriveTheme {
                DriveScreen(state = state, folder = folder, onFolder = { folder = it },
                    configure = drive::configure, login = drive::login, refresh = drive::refresh,
                    createFolder = drive::addFolder, delete = drive::delete, logout = drive::logout,
                    reconnect = drive::reconnect, clearNotice = drive::clearNotice,
                    editCredentials = drive::editConfiguration,
                    upload = {
                        uploadFolder = folder
                        upload.launch(arrayOf("*/*"))
                    },
                    download = { file ->
                        pendingDownloadId = file.messageId
                        download.launch(file.name)
                    })
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("uploadFolder", uploadFolder)
        outState.putLong("downloadId", pendingDownloadId)
        super.onSaveInstanceState(outState)
    }
}
