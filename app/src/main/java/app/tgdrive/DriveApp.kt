package app.tgdrive

import android.app.Application
import app.tgdrive.data.TelegramDrive

class DriveApp : Application() {
    val drive: TelegramDrive by lazy { TelegramDrive(this) }
}
