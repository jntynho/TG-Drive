package app.tgdrive.data

import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

internal interface TdConnection {
    fun send(function: TdApi.Function<*>, result: (TdApi.Object) -> Unit)
}

internal fun interface TdConnectionFactory {
    fun create(onUpdate: (TdApi.Object) -> Unit, onError: () -> Unit): TdConnection
}

internal object NativeTdConnectionFactory : TdConnectionFactory {
    override fun create(onUpdate: (TdApi.Object) -> Unit, onError: () -> Unit): TdConnection {
        System.loadLibrary("tdjni")
        Client.execute(TdApi.SetLogVerbosityLevel(0))
        val client = Client.create(onUpdate, { onError() }, { onError() })
        return object : TdConnection {
            override fun send(function: TdApi.Function<*>, result: (TdApi.Object) -> Unit) {
                client.send(function) { result(it) }
            }
        }
    }
}

internal interface SessionSettings {
    fun put(name: String, value: String)
    fun get(name: String): String?
    fun databaseKey(): ByteArray
}
