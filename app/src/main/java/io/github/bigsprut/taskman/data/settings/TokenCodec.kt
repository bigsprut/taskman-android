package io.github.bigsprut.taskman.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Шифрование токена доступа.
 *
 * Интерфейс отдельный, потому что Android Keystore недоступен в JVM-тестах: тестируемое
 * поведение (хранение, вытеснение, отказ при потере ключа) должно оставаться проверяемым,
 * а реализация на устройстве — минимальной.
 */
interface TokenCodec {
    fun encrypt(plainText: String): String

    /** `null`, если расшифровать нельзя (ключ утерян/данные повреждены) — это повод войти заново. */
    fun decrypt(cipherText: String): String?
}

/**
 * AES/GCM-ключ в Android Keystore: сам токен на диск попадает только шифротекстом.
 *
 * Формат: `v1:` + Base64(iv ‖ ciphertext). Префикс версии оставлен, чтобы будущую смену
 * ключа/алгоритма можно было сделать без миграции вслепую.
 *
 * Ключ не покидает Keystore (неэкспортируемый). `allowBackup=false` в манифесте: резервная
 * копия не должна выносить шифротекст без ключа, а сам ключ в бэкап не попадает.
 */
class AndroidKeystoreTokenCodec(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : TokenCodec {

    override fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val payload = ByteArray(1 + iv.size + cipherText.size)
        payload[0] = iv.size.toByte()
        iv.copyInto(payload, destinationOffset = 1)
        cipherText.copyInto(payload, destinationOffset = 1 + iv.size)
        return PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    override fun decrypt(cipherText: String): String? = runCatching {
        require(cipherText.startsWith(PREFIX)) { "unknown token format" }
        val payload = Base64.decode(cipherText.removePrefix(PREFIX), Base64.NO_WRAP)
        require(payload.size > 1) { "token payload too short" }
        val ivSize = payload[0].toInt()
        require(ivSize in 1 until payload.size) { "bad iv size" }
        val iv = payload.copyOfRange(1, 1 + ivSize)
        val body = payload.copyOfRange(1 + ivSize, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_KEY_ALIAS = "taskman_token_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFIX = "v1:"
        const val GCM_TAG_BITS = 128
        const val KEY_SIZE_BITS = 256
    }
}
