package app.tgdrive.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import org.drinkless.tdlib.TdApi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TelegramConnectionTest {
    @Test fun `phone state received before parameters acknowledgement is never overwritten`() = runTest {
        val transport = FakeTdFactory()
        val drive = TelegramDrive(RuntimeEnvironment.getApplication(), FakeSettings(), transport, backgroundScope)
        drive.configure("12345", "a".repeat(32)) // Test fixture; fake transport never connects to Telegram.
        runCurrent()
        transport.emit(TdApi.AuthorizationStateWaitTdlibParameters())
        runCurrent()
        assertTrue(transport.pending.any { it.first is TdApi.SetTdlibParameters })

        // TDLib can publish the next auth state before acknowledging SetTdlibParameters.
        transport.emit(TdApi.AuthorizationStateWaitPhoneNumber())
        runCurrent()
        transport.reply(TdApi.SetTdlibParameters::class.java, TdApi.Ok())
        runCurrent()
        assertEquals(LoginStep.PHONE, drive.state.value.login)
    }

    @Test fun `successful initialization remains on phone after watchdog deadline`() = runTest {
        val transport = FakeTdFactory()
        val drive = TelegramDrive(RuntimeEnvironment.getApplication(), FakeSettings(), transport, backgroundScope)
        drive.configure("12345", "a".repeat(32)); runCurrent()
        transport.emit(TdApi.AuthorizationStateWaitTdlibParameters()); runCurrent()
        transport.reply(TdApi.SetTdlibParameters::class.java, TdApi.Ok()); runCurrent()
        transport.emit(TdApi.AuthorizationStateWaitPhoneNumber()); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(LoginStep.PHONE, drive.state.value.login)
        assertNull(drive.state.value.authError)
    }

    @Test fun `rejected parameters produce persistent error instead of spinner and redact API hash`() = runTest {
        val transport = FakeTdFactory()
        val hash = "a".repeat(32)
        val drive = TelegramDrive(RuntimeEnvironment.getApplication(), FakeSettings(), transport, backgroundScope)
        drive.configure("12345", hash); runCurrent()
        transport.emit(TdApi.AuthorizationStateWaitTdlibParameters()); runCurrent()
        transport.reply(TdApi.SetTdlibParameters::class.java, TdApi.Error(400, "Invalid api_hash $hash")); runCurrent()
        assertEquals(LoginStep.CONNECTION_ERROR, drive.state.value.login)
        assertFalse(drive.state.value.busy)
        drive.clearNotice()
        assertTrue(drive.state.value.authError!!.contains("Invalid api_hash"))
        assertFalse(drive.state.value.authError!!.contains(hash))
    }

    @Test fun `silent initialization stops waiting after timeout`() = runTest {
        val transport = FakeTdFactory()
        val drive = TelegramDrive(RuntimeEnvironment.getApplication(), FakeSettings(), transport, backgroundScope)
        drive.configure("12345", "a".repeat(32)); runCurrent()
        advanceTimeBy(30_001); runCurrent()
        assertEquals(LoginStep.CONNECTION_ERROR, drive.state.value.login)
        assertTrue(drive.state.value.authError!!.contains("timed out"))
    }

    @Test fun `parameters with missing acknowledgement time out and a late phone update still recovers`() = runTest {
        val transport = FakeTdFactory()
        val drive = TelegramDrive(RuntimeEnvironment.getApplication(), FakeSettings(), transport, backgroundScope)
        drive.configure("12345", "a".repeat(32)); runCurrent()
        transport.emit(TdApi.AuthorizationStateWaitTdlibParameters()); runCurrent()
        advanceTimeBy(30_001); runCurrent()
        assertEquals(LoginStep.CONNECTION_ERROR, drive.state.value.login)
        // Native requests cannot always be cancelled; late updates must remain usable.
        transport.reply(TdApi.SetTdlibParameters::class.java, TdApi.Ok())
        transport.emit(TdApi.AuthorizationStateWaitPhoneNumber()); runCurrent()
        assertEquals(LoginStep.PHONE, drive.state.value.login)
        assertNull(drive.state.value.authError)
    }

    @Test fun `retry queries current native state without creating duplicate clients`() = runTest {
        val transport = FakeTdFactory()
        val drive = TelegramDrive(RuntimeEnvironment.getApplication(), FakeSettings(), transport, backgroundScope)
        drive.configure("12345", "a".repeat(32)); runCurrent()
        advanceTimeBy(30_001); runCurrent()
        drive.reconnect(); runCurrent()
        transport.reply(TdApi.GetAuthorizationState::class.java, TdApi.AuthorizationStateWaitPhoneNumber()); runCurrent()
        assertEquals(1, transport.connections.size)
        assertEquals(LoginStep.PHONE, drive.state.value.login)
    }

    @Test fun `editing credentials closes old client without logout or deletion and ignores stale updates`() = runTest {
        val transport = FakeTdFactory()
        val drive = TelegramDrive(RuntimeEnvironment.getApplication(), FakeSettings(), transport, backgroundScope)
        drive.configure("12345", "a".repeat(32)); runCurrent()
        val old = transport.connections.single()
        drive.editConfiguration()
        drive.configure("67890", "b".repeat(32)); runCurrent()
        assertEquals(1, transport.connections.size)
        transport.reply(TdApi.Close::class.java, TdApi.Ok())
        transport.emit(TdApi.AuthorizationStateClosed()); runCurrent()
        assertEquals(2, transport.connections.size)
        transport.emit(TdApi.AuthorizationStateWaitTdlibParameters()); runCurrent()
        val params = transport.pending.single { it.first is TdApi.SetTdlibParameters }.first as TdApi.SetTdlibParameters
        assertEquals(67890, params.apiId)
        assertEquals("b".repeat(32), params.apiHash)
        transport.reply(TdApi.SetTdlibParameters::class.java, TdApi.Ok()); runCurrent()
        transport.emit(TdApi.AuthorizationStateWaitPhoneNumber()); runCurrent()
        old(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateClosed())); runCurrent()
        assertEquals(LoginStep.PHONE, drive.state.value.login)
        assertFalse(transport.sent.any { it is TdApi.LogOut || it is TdApi.Destroy })
    }

    @Test fun `native loading failure is recoverable rather than stuck connecting`() = runTest {
        val unavailable = TdConnectionFactory { _, _ -> throw UnsatisfiedLinkError("test ABI unavailable") }
        val drive = TelegramDrive(RuntimeEnvironment.getApplication(), FakeSettings(), unavailable, backgroundScope)
        drive.configure("12345", "a".repeat(32)); runCurrent()
        assertEquals(LoginStep.CONNECTION_ERROR, drive.state.value.login)
        assertTrue(drive.state.value.authError!!.contains("UnsatisfiedLinkError"))
        drive.editConfiguration()
        assertEquals(LoginStep.CONFIGURE, drive.state.value.login)
    }

    @Test fun `network status updates explain offline state without preventing local phone prompt`() = runTest {
        val transport = FakeTdFactory()
        val drive = TelegramDrive(RuntimeEnvironment.getApplication(), FakeSettings(), transport, backgroundScope)
        drive.configure("12345", "a".repeat(32)); runCurrent()
        transport.update(TdApi.UpdateConnectionState(TdApi.ConnectionStateWaitingForNetwork()))
        transport.emit(TdApi.AuthorizationStateWaitPhoneNumber()); runCurrent()
        assertEquals(TelegramNetwork.WAITING_FOR_NETWORK, drive.state.value.network)
        assertEquals(LoginStep.PHONE, drive.state.value.login)
    }

    @Test fun `sign-in request timeout restores controls and keeps an actionable error`() = runTest {
        val transport = FakeTdFactory()
        val drive = TelegramDrive(RuntimeEnvironment.getApplication(), FakeSettings(), transport, backgroundScope)
        drive.configure("12345", "a".repeat(32)); runCurrent()
        transport.emit(TdApi.AuthorizationStateWaitPhoneNumber()); runCurrent()
        drive.login("+123456789"); runCurrent()
        assertTrue(drive.state.value.busy)
        advanceTimeBy(30_001); runCurrent()
        assertEquals(LoginStep.PHONE, drive.state.value.login)
        assertFalse(drive.state.value.busy)
        assertTrue(drive.state.value.authError!!.contains("did not respond"))
    }

    private class FakeSettings : SessionSettings {
        private val values = mutableMapOf<String, String>()
        override fun get(name: String): String = values[name] ?: ""
        override fun put(name: String, value: String) { values[name] = value }
        override fun databaseKey() = ByteArray(32)
    }
    private class FakeTdFactory : TdConnectionFactory {
        lateinit var update: (TdApi.Object) -> Unit
        val connections = mutableListOf<(TdApi.Object) -> Unit>()
        val sent = mutableListOf<TdApi.Function<*>>()
        val pending = mutableListOf<Pair<TdApi.Function<*>, (TdApi.Object) -> Unit>>()
        override fun create(onUpdate: (TdApi.Object) -> Unit, onError: () -> Unit): TdConnection {
            update = onUpdate
            connections.add(onUpdate)
            return object : TdConnection {
                override fun send(function: TdApi.Function<*>, result: (TdApi.Object) -> Unit) {
                    sent.add(function); pending.add(function to result)
                }
            }
        }
        fun emit(state: TdApi.AuthorizationState) { update(TdApi.UpdateAuthorizationState(state)) }
        fun reply(type: Class<*>, result: TdApi.Object) {
            val call = pending.first { type.isInstance(it.first) }
            pending.remove(call)
            call.second(result)
        }
    }
}
