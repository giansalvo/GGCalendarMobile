package io.github.giansalvo.ggcalendar.core.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps the address of a subscription (a secret for a private calendar) unreadable in the database. */
interface UrlCipher {
    /** The text to store. */
    fun encrypt(plain: String): String

    /** The address, or null when it cannot be read (the key is gone, the text was changed). */
    fun decrypt(stored: String): String?
}

/**
 * AES-256 in GCM mode with a key that lives in the Android Keystore and never leaves it. Stored as "v1:" and the Base64 of the random
 * 12-byte IV followed by the encrypted text and its 16-byte tag.
 */
class KeystoreUrlCipher : UrlCipher {
    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    override fun decrypt(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return null
        return try {
            val bytes = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (bytes.size <= IV_BYTES + TAG_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BYTES * 8, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val PREFIX = "v1:"
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "ggcalendar.subscription.urls"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
    }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
object SubscriptionModule {
    @dagger.Provides
    @javax.inject.Singleton
    fun urlCipher(): UrlCipher = KeystoreUrlCipher()
}
