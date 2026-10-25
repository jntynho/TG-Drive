package app.tgdrive.library

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SendCooldownException(val until:Long):IllegalStateException("Telegram paused sending. Automatic resume after the account countdown.")

internal fun telegramWaitMillis(message:String):Long? = Regex("(?:FLOOD_WAIT[_ :]*|retry after\\s+)(\\d+)",RegexOption.IGNORE_CASE)
    .find(message)?.groupValues?.get(1)?.toLongOrNull()?.takeIf{it>0}?.let{if(it>Long.MAX_VALUE/1000)Long.MAX_VALUE else it*1000}

/** One account-wide send lane; a server wait is durable and cannot be bypassed by Retry. */
class TelegramSendCoordinator(private val clock:()->Long={now()},private val persist:suspend(Long)->Unit={}) {
    private val lane=Mutex()
    private val state=Mutex()
    @Volatile var until=0L;private set
    private var last=0L
    private var spacing=350L
    private var successes=0
    fun restore(saved:Long){until=saved.coerceAtLeast(0)}
    fun checkAllowed() { if(until>clock())throw SendCooldownException(until) }
    /** Content uploads may overlap; any exposed server wait still freezes the account lane. */
    suspend fun <T> content(block:suspend()->T):T {
        checkAllowed()
        return try{block()}catch(e:Exception){
            if(telegramWaitMillis(e.message.orEmpty())!=null||e is app.tgdrive.data.TelegramRequestException&&e.code==429)
                state.withLock{recordFailure(e)}else throw e
        }
    }
    private suspend fun recordFailure(e:Exception):Nothing {
        val wait=telegramWaitMillis(e.message.orEmpty())
        if(wait!=null || e is app.tgdrive.data.TelegramRequestException && e.code==429) {
            val duration=wait?:60_000;val start=clock()
            until=maxOf(until,if(duration>Long.MAX_VALUE-start-1000)Long.MAX_VALUE else start+duration+1000)
            spacing=(spacing*2).coerceAtMost(4000);successes=0
            persist(until)
            throw SendCooldownException(until)
        }
        throw e
    }
    suspend fun <T> send(block:suspend()->T):T = lane.withLock {
        val pause=state.withLock{checkAllowed();last+spacing-clock()}
        if(pause>0)delay(pause)
        // A content-stage wait can arrive while this publication is spacing or awaiting acknowledgement.
        checkAllowed()
        try {
            val result=block()
            state.withLock {
                last=clock()
                if(until<=clock()) {
                    successes++
                    if(successes>=12){spacing=(spacing*9/10).coerceAtLeast(200);successes=0}
                }
            }
            result
        } catch(e:Exception) { state.withLock{recordFailure(e)} }
    }
}
