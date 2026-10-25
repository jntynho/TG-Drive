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
    @Volatile var until=0L;private set
    private var last=0L
    private var spacing=350L
    private var successes=0
    fun restore(saved:Long){until=saved.coerceAtLeast(0)}
    suspend fun <T> send(block:suspend()->T):T = lane.withLock {
        if(until>clock())throw SendCooldownException(until)
        val pause=last+spacing-clock()
        if(pause>0)delay(pause)
        try {
            block().also {
                last=clock();successes++
                if(successes>=12){spacing=(spacing*9/10).coerceAtLeast(200);successes=0}
            }
        } catch(e:Exception) {
            val wait=telegramWaitMillis(e.message.orEmpty())
            if(wait!=null || e is app.tgdrive.data.TelegramRequestException && e.code==429) {
                val duration=wait?:60_000
                val start=clock()
                until=maxOf(until,if(duration>Long.MAX_VALUE-start-1000)Long.MAX_VALUE else start+duration+1000)
                spacing=(spacing*2).coerceAtMost(4000);successes=0
                persist(until)
                throw SendCooldownException(until)
            }
            throw e
        }
    }
}
