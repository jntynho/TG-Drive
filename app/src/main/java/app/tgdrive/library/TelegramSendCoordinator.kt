package app.tgdrive.library

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SendCooldownException(val until:Long):IllegalStateException("Telegram paused sending. Automatic resume after the account countdown.")

internal fun telegramWaitMillis(message:String):Long? = Regex("(?:FLOOD_(?:PREMIUM_)?WAIT[_ :]*|SLOWMODE_WAIT[_ :]*|retry[ _]after[ :]*)(\\d+)",RegexOption.IGNORE_CASE)
    .find(message)?.groupValues?.get(1)?.let{it.toLongOrNull()?:Long.MAX_VALUE}?.takeIf{it>0}?.let{if(it>Long.MAX_VALUE/1000)Long.MAX_VALUE else it*1000}

/** Serialized publication without an invented success delay; explicit server waits remain durable. */
class TelegramSendCoordinator(private val clock:()->Long={now()},private val persist:suspend(Long)->Unit={}) {
    private val lane=Mutex()
    private val state=Mutex()
    @Volatile var until=0L;private set
    fun restore(saved:Long){until=saved.coerceAtLeast(0)}
    fun checkAllowed() { if(until>clock())throw SendCooldownException(until) }
    /** Also used for a rate limit discovered while reconciling a remote document. */
    suspend fun recordRateLimit(error:Exception):Long? {
        if(error !is app.tgdrive.data.TelegramRequestException)return null
        val wait=telegramWaitMillis(error.message.orEmpty())
        if(wait==null&&error.code!=429)return null
        return withContext(NonCancellable){state.withLock{
            val duration=wait?:60_000L
            val start=clock().coerceAtLeast(0)
            until=maxOf(until,if(duration>Long.MAX_VALUE-start-1000)Long.MAX_VALUE else start+duration+1000)
            persist(until)
            until
        }}
    }
    suspend fun <T> content(block:suspend()->T):T {
        checkAllowed()
        return try{block()}catch(e:Exception){
            recordRateLimit(e)?.let{throw SendCooldownException(it)}
            throw e
        }
    }
    suspend fun <T> send(block:suspend()->T):T = lane.withLock {
        checkAllowed()
        try{block()}catch(e:Exception){
            recordRateLimit(e)?.let{throw SendCooldownException(it)}
            throw e
        }
    }
}
