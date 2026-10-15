package com.jianyu.ds_pet.pet

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** DeepSeek API Key 经 Android Keystore 的 AES/GCM 加密后放进私有偏好。明文不进配置，也不进日志。 */
class ApiKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasKey(): Boolean = !prefs.getString(KEY_BLOB, null).isNullOrEmpty()

    fun save(plain: String) {
        val text = plain.trim()
        require(text.isNotEmpty())
        require(text.length <= 512)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(4 + iv.size + cipherText.size)
        packed[0] = (iv.size shr 24).toByte()
        packed[1] = (iv.size shr 16).toByte()
        packed[2] = (iv.size shr 8).toByte()
        packed[3] = iv.size.toByte()
        iv.copyInto(packed, 4)
        cipherText.copyInto(packed, 4 + iv.size)
        prefs.edit().putString(KEY_BLOB, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun load(): String? {
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            if (raw.size < 5) error("short")
            val ivLen = ((raw[0].toInt() and 0xff) shl 24) or
                ((raw[1].toInt() and 0xff) shl 16) or
                ((raw[2].toInt() and 0xff) shl 8) or
                (raw[3].toInt() and 0xff)
            if (ivLen !in 12..16 || raw.size < 4 + ivLen + 1) error("iv")
            val iv = raw.copyOfRange(4, 4 + ivLen)
            val cipherText = raw.copyOfRange(4 + ivLen, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            if (e is AEADBadTagException || e is BadPaddingException) clear()
            null
        } catch (_: IllegalArgumentException) {
            clear()
            null
        } catch (_: IllegalStateException) {
            clear()
            null
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_BLOB).apply()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "pet_secret"
        const val KEY_BLOB = "blob"
        const val ALIAS = "dsh_pet_api_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
