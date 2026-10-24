package app.tgdrive.library

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VaultManagerTest {
    @Test fun `password and recovery each unlock a portable vault`() {
        val first = VaultManager(); val (descriptor,recovery) = first.create("Documents", "a strong test password".toCharArray())
        val op = first.protect(Envelope(kind = EntityKind.ITEM, entityId = "one", deviceId = "phone", vaultId = descriptor.id, body = "sensitive metadata"))
        assertFalse(op.body.contains("sensitive")); first.lock()
        val second = VaultManager(); second.unlock(descriptor, recovery.toCharArray(), true); assertEquals("sensitive metadata", second.body(op))
        second.lock(); second.unlock(descriptor, "a strong test password".toCharArray()); assertEquals("sensitive metadata", second.body(op))
    }
    @Test fun `authenticated streaming handles multiple segments and rejects context substitution`() {
        val vault = VaultManager(); val (desc,_) = vault.create("Files", "another good test password".toCharArray())
        val source = ByteArray(2_500_000) { (it % 251).toByte() }; val encrypted = ByteArrayOutputStream()
        vault.encrypt(desc.id, source.inputStream(), encrypted, "file/version/0")
        val result = ByteArrayOutputStream(); vault.decrypt(desc.id, encrypted.toByteArray().inputStream(), result, "file/version/0")
        assertArrayEquals(source, result.toByteArray())
        assertTrue(runCatching { vault.decrypt(desc.id, encrypted.toByteArray().inputStream(), ByteArrayOutputStream(), "other/version/0") }.isFailure)
        val truncated = encrypted.toByteArray().dropLast(7).toByteArray()
        assertTrue(runCatching { vault.decrypt(desc.id, truncated.inputStream(), ByteArrayOutputStream(), "file/version/0") }.isFailure)
    }
    @Test fun `locked vault and modified metadata header cannot be opened`() {
        val vault = VaultManager(); val (descriptor,_) = vault.create("Private", "private test password long".toCharArray())
        val op = vault.protect(Envelope(kind = EntityKind.FOLDER, entityId = "f", deviceId = "p", vaultId = descriptor.id, body = "private name"))
        assertTrue(runCatching { vault.body(op.copy(entityId = "other")) }.isFailure)
        vault.lock(); assertTrue(runCatching { vault.body(op) }.isFailure)
        assertTrue(runCatching { vault.unlock(descriptor, "wrong password".toCharArray()) }.isFailure)
    }
}
