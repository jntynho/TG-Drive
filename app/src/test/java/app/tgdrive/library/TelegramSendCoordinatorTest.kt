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

}
