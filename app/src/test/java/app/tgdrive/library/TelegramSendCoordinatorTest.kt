package app.tgdrive.library

import app.tgdrive.data.TelegramRequestException
import kotlinx.coroutines.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramSendCoordinatorTest {
    @Test fun serverWaitStopsOtherSendsAndSurvivesRecreation()=runTest {
        var saved=0L;var calls=0
        val sender=TelegramSendCoordinator({testScheduler.currentTime},{saved=it})
        try{sender.send<Unit>{calls++;throw TelegramRequestException(429,"Too Many Requests: retry after 160")};fail()}catch(_:SendCooldownException){}
        assertEquals(1,calls);assertTrue(saved>=161000)
        val restored=TelegramSendCoordinator({testScheduler.currentTime},{saved=it}).apply{restore(saved)}
        try{restored.send{calls++};fail()}catch(_:SendCooldownException){}
        assertEquals(1,calls)
        advanceTimeBy(saved-testScheduler.currentTime)
        restored.send{calls++};assertEquals(2,calls)
    }
    @Test fun ordinaryErrorsDoNotInventAccountCooldown()=runTest {
        val sender=TelegramSendCoordinator({testScheduler.currentTime})
        try{sender.send<Unit>{error("Source permission denied")}}catch(_:IllegalStateException){}
        assertEquals(0,sender.until)
        assertEquals(160000L,telegramWaitMillis("FLOOD_WAIT_160"))
        assertNull(telegramWaitMillis("file 160 not found"))
    }
}
