package app.tgdrive.library

import app.tgdrive.data.TelegramRequestException
import kotlinx.coroutines.test.*
import kotlinx.coroutines.*
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
        assertNull(sender.recordRateLimit(IllegalStateException("Source FLOOD_WAIT_160 not found")))
        assertEquals(0L,sender.until)
    }
    @Test fun slowContentDoesNotOccupyThePublicationLane()=runTest {
        val sender=TelegramSendCoordinator({testScheduler.currentTime})
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val content=launch{sender.content{started.complete(Unit);release.await()}}
        started.await()
        var published=false;sender.send{published=true}
        assertTrue(published);assertTrue(content.isActive)
        release.complete(Unit);content.join()
    }
    @Test fun longContentFloodWaitIsNeverShortenedAndBlocksPublication()=runTest {
        var saved=0L;val sender=TelegramSendCoordinator({testScheduler.currentTime},{saved=it})
        try{sender.content<Unit>{throw TelegramRequestException(429,"FLOOD_WAIT_900")};fail()}catch(_:SendCooldownException){}
        assertTrue(saved>=901_000)
        var called=false
        try{sender.send{called=true};fail()}catch(_:SendCooldownException){}
        assertFalse(called)
    }
    @Test fun ordinaryContentFailureDoesNotWaitForAnUnrelatedPublisher()=runTest {
        val sender=TelegramSendCoordinator({testScheduler.currentTime})
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val publishing=launch{sender.send{started.complete(Unit);release.await()}}
        started.await()
        try{sender.content<Unit>{error("Source permission denied")};fail()}catch(e:IllegalStateException){assertTrue(e.message!!.contains("permission"))}
        assertTrue(publishing.isActive);release.complete(Unit);publishing.join()
    }

    @Test fun contentFloodIsPersistedEvenWhileAnotherMessageAwaitsAcknowledgement()=runTest {
        var saved=0L;val sender=TelegramSendCoordinator({testScheduler.currentTime},{saved=it})
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val publishing=launch{sender.send{started.complete(Unit);release.await()}}
        started.await()
        try{sender.content<Unit>{throw TelegramRequestException(429,"FLOOD_WAIT_160")};fail()}catch(_:SendCooldownException){}
        assertTrue(saved>=161_000);assertTrue(publishing.isActive)
        release.complete(Unit);publishing.join()
        try{sender.send{fail("A completed older publisher must not clear the newer cooldown")}}catch(_:SendCooldownException){}
    }

    @Test fun healthyMessagesHaveNoInventedGapAndRemainSerialized()=runTest {
        val sender=TelegramSendCoordinator({testScheduler.currentTime})
        val order=mutableListOf<Int>();var active=0;var maximum=0
        coroutineScope{repeat(50){i->launch{sender.send{active++;maximum=maxOf(maximum,active);yield();order.add(i);active--}}}}
        assertEquals((0 until 50).toList(),order)
        assertEquals(1,maximum)
        assertEquals(0L,testScheduler.currentTime)
    }
    @Test fun premiumAndHugeServerWaitsCannotOverflowIntoImmediateRetry()=runTest {
        assertEquals(900_000L,telegramWaitMillis("FLOOD_PREMIUM_WAIT_900"))
        assertEquals(Long.MAX_VALUE,telegramWaitMillis("FLOOD_WAIT_92233720368547758070"))
        val sender=TelegramSendCoordinator({testScheduler.currentTime})
        val deadline=sender.recordRateLimit(TelegramRequestException(429,"FLOOD_PREMIUM_WAIT_900"))
        assertEquals(901_000L,deadline)
        try{sender.send{fail("Server wait must not be bypassed")}}catch(_:SendCooldownException){}
    }

}
