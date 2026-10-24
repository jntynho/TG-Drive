package app.tgdrive

import android.app.Application
import app.tgdrive.data.TelegramDrive

class DriveApp : Application() {
    val sessions: SessionPool by lazy { SessionPool(this) }
    val drive: TelegramDrive get() = sessions.active().drive
    private val authorizations = java.util.concurrent.ConcurrentHashMap<String,Long>()
    fun authorize(profile: String) { authorizations[profile] = Long.MAX_VALUE }
    fun authorized(profile: String) = (authorizations[profile] ?: 0L) > android.os.SystemClock.elapsedRealtime()
    fun backgrounded() { val until = android.os.SystemClock.elapsedRealtime() + 30_000; authorizations.keys.forEach { authorizations[it] = until } }
}
