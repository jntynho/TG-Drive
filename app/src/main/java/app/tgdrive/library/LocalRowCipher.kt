package app.tgdrive.library

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface RowCodec {
    fun encode(table: String, id: String, value: String): String
    fun decode(table: String, id: String, value: String): String
}
object PlainRows : RowCodec {
    override fun encode(table: String, id: String, value: String) = value
    override fun decode(table: String, id: String, value: String) = value
}
/** Encrypts local row payloads, including source URIs and private task titles. IDs remain queryable. */
class LocalRowCipher(accountId: Long) : RowCodec {
    private val key: SecretKey
    init {
        val alias = "tgdrive.library.$accountId"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        key = synchronized(creationLock) { (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        } }
    }
    override fun encode(table: String, id: String, value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key); updateAAD("$table:$id".toByteArray())
        }
        check(cipher.iv.size == 12) { "Unsupported local encryption nonce length" }
        return "enc1:" + Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()))
    }
    override fun decode(table: String, id: String, value: String): String {
        require(value.startsWith("enc1:")) { "Unexpected unprotected library row. Do not reset data; restore an index checkpoint if the installation key is unavailable." }
        val bytes = Base64.getDecoder().decode(value.removePrefix("enc1:")); require(bytes.size >= 28)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            updateAAD("$table:$id".toByteArray()); doFinal(bytes, 12, bytes.size - 12).decodeToString()
        }
    }
    companion object { private val creationLock = Any() }
}
