package app.tgdrive.library

import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.streamingaead.StreamingAeadKeyTemplates
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.SeekableByteChannel
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Tink authenticated streaming encryption; portable password/recovery wraps, never Telegram session keys. */
class VaultManager {
    private data class Keys(val metadata: ByteArray, val streaming: StreamingAead)
    private val opened = mutableMapOf<String, Keys>()
    init { StreamingAeadConfig.register() }
    val unlockedIds: Set<String> get() = synchronized(opened) { opened.keys.toSet() }
    fun isUnlocked(id: String) = id.isEmpty() || synchronized(opened) { id in opened }

    fun create(label: String, password: CharArray): Pair<VaultDescriptor, String> {
        require(password.size >= 12) { "Use a vault password of at least 12 characters." }
        val id = newId(); val salt = random(16); val recovery = random(32)
        val streamHandle = KeysetHandle.generateNew(StreamingAeadKeyTemplates.AES256_GCM_HKDF_1MB)
        val stream = ByteArrayOutputStream().also { CleartextKeysetHandle.write(streamHandle, BinaryKeysetWriter.withOutputStream(it)) }.toByteArray()
        val bundle = (b64(random(32)) + ":" + b64(stream)).toByteArray()
        val derived = derive(password, salt, 600_000)
        try {
            val descriptor = VaultDescriptor(id, cleanName(label), b64(salt), passwordWrap = seal(bundle, derived, "$id:password".toByteArray()), recoveryWrap = seal(bundle, recovery, "$id:recovery".toByteArray()))
            openBundle(id, bundle)
            return descriptor to recovery.joinToString("") { "%02x".format(it) }
        } finally { password.fill('\u0000'); derived.fill(0); bundle.fill(0); recovery.fill(0); stream.fill(0) }
    }
    fun unlock(descriptor: VaultDescriptor, credential: CharArray, recovery: Boolean = false) {
        val key = if (recovery) {
            val text = String(credential).replace(" ", "").replace("-", "")
            require(text.matches(Regex("[0-9a-fA-F]{64}"))) { "Recovery key must contain 64 hexadecimal characters." }
            text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } else derive(credential, unb64(descriptor.salt), descriptor.iterations)
        try {
            val purpose = if (recovery) "recovery" else "password"
            val bundle = unseal(if (recovery) descriptor.recoveryWrap else descriptor.passwordWrap, key, "${descriptor.id}:$purpose".toByteArray())
            try { openBundle(descriptor.id, bundle) } finally { bundle.fill(0) }
        } catch (e: Exception) { throw IllegalArgumentException("Could not unlock the vault. Check the credential or recovery key.", e) }
        finally { credential.fill('\u0000'); key.fill(0) }
    }
    private fun openBundle(id: String, bytes: ByteArray) {
        val split = bytes.decodeToString().split(':', limit = 2)
        require(split.size == 2)
        val metadata = unb64(split[0]); require(metadata.size == 32)
        val handle = CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(unb64(split[1])))
        synchronized(opened) { opened[id] = Keys(metadata, handle.getPrimitive(StreamingAead::class.java)) }
    }
    fun lock() = synchronized(opened) { opened.values.forEach { it.metadata.fill(0) }; opened.clear() }
    private fun keys(id: String) = synchronized(opened) { opened[id] } ?: error("Unlock the vault to continue.")
    private fun aad(op: Envelope) = listOf(op.operationId, op.kind.name, op.entityId, op.parentOperationId, op.deviceId, op.timestamp.toString(), op.vaultId, op.tombstone.toString(), op.resolves.sorted().joinToString(",")).joinToString("|").toByteArray()
    private fun metadataKey(id: String) = synchronized(opened) { (opened[id] ?: error("Unlock the vault to continue.")).metadata.copyOf() }
    fun protect(op: Envelope): Envelope {
        if (op.vaultId.isEmpty()) return op
        val key = metadataKey(op.vaultId)
        return try { op.copy(body = seal(op.body.toByteArray(), key, aad(op))) } finally { key.fill(0) }
    }
    fun body(op: Envelope): String {
        if (op.vaultId.isEmpty()) return op.body
        val key = metadataKey(op.vaultId)
        return try { unseal(op.body, key, aad(op)).decodeToString() } finally { key.fill(0) }
    }
    fun encrypt(id: String, source: InputStream, destination: OutputStream, context: String) {
        keys(id).streaming.newEncryptingStream(destination, context.toByteArray()).use { source.copyTo(it) }
    }
    fun decrypt(id: String, source: InputStream, destination: OutputStream, context: String) {
        keys(id).streaming.newDecryptingStream(source, context.toByteArray()).use { it.copyTo(destination) }
    }
    fun decryptingChannel(id: String, channel: SeekableByteChannel, context: String): SeekableByteChannel = keys(id).streaming.newSeekableDecryptingChannel(channel, context.toByteArray())

    companion object {
        private fun random(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
        private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
        private fun unb64(value: String) = Base64.getDecoder().decode(value)
        private fun derive(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
            require(iterations in 100_000..2_000_000 && salt.size == 16) { "Unsupported vault parameters" }
            val spec = PBEKeySpec(password, salt, iterations, 256)
            return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        }
        private fun seal(bytes: ByteArray, key: ByteArray, aad: ByteArray): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")); updateAAD(aad) }
            return b64(cipher.iv + cipher.doFinal(bytes))
        }
        private fun unseal(value: String, key: ByteArray, aad: ByteArray): ByteArray {
            val bytes = unb64(value); require(bytes.size >= 28)
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                updateAAD(aad); doFinal(bytes, 12, bytes.size - 12)
            }
        }
    }
}
