package org.autojs.autojs.mgmt.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.preference.PreferenceManager
import org.autojs.autojs.app.GlobalAppContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 账号令牌加密持久化 (方案 §10.1 AccountSession: "微信式长久登录")。
 *
 * 零新增第三方依赖: 直接使用 AndroidKeyStore 生成的 AES/GCM 密钥 (API 23+,
 * 工程 minSdk=24), 密文 Base64(iv || ciphertext) 存入默认 SP;
 * 私钥材料不出 Keystore, 卸载应用后 Keystore 别名随 UID 清除。
 *
 * 任何加解密异常都按"无会话"降级 (清掉残值), 绝不因薄层存储问题崩溃宿主。
 */
internal object AccountTokenStore {

    private const val TAG = "MgmtAccountStore"

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "mgmt_account_aes_gcm"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private const val SP_KEY_USERNAME = "mgmt_account_username"
    private const val SP_KEY_ACCESS_TOKEN = "mgmt_account_access_token"
    private const val SP_KEY_REFRESH_TOKEN = "mgmt_account_refresh_token"
    private const val SP_KEY_EXPIRE_AT = "mgmt_account_expire_at"

    data class SavedSession(
        val username: String,
        val accessToken: String,
        val refreshToken: String,
        val expireAtMillis: Long,
    )

    private val prefs
        get() = PreferenceManager.getDefaultSharedPreferences(GlobalAppContext.get())

    @Synchronized
    fun save(username: String, accessToken: String, refreshToken: String, expireAtMillis: Long) {
        val encAccess = encrypt(accessToken) ?: return
        // 当前后端 refresh_token 与 access_token 同值 (无独立刷新端点),
        // 仍分字段持久化, 将来服务端启用独立 refresh token 时无需改存储契约。
        val encRefresh = encrypt(refreshToken.ifEmpty { accessToken }) ?: return
        prefs.edit()
            .putString(SP_KEY_USERNAME, username)
            .putString(SP_KEY_ACCESS_TOKEN, encAccess)
            .putString(SP_KEY_REFRESH_TOKEN, encRefresh)
            .putLong(SP_KEY_EXPIRE_AT, expireAtMillis)
            .apply()
    }

    @Synchronized
    fun load(): SavedSession? {
        val username = prefs.getString(SP_KEY_USERNAME, null)?.takeIf { it.isNotEmpty() }
        val encAccess = prefs.getString(SP_KEY_ACCESS_TOKEN, null)
        if (username == null || encAccess == null) return null
        val accessToken = decrypt(encAccess)?.takeIf { it.isNotEmpty() } ?: return clearAndNull()
        val encRefresh = prefs.getString(SP_KEY_REFRESH_TOKEN, null)
        val refreshToken = encRefresh?.let { decrypt(it) }?.takeIf { it.isNotEmpty() } ?: accessToken
        val expireAt = prefs.getLong(SP_KEY_EXPIRE_AT, 0L)
        return SavedSession(username, accessToken, refreshToken, expireAt)
    }

    @Synchronized
    fun clear() {
        prefs.edit()
            .remove(SP_KEY_USERNAME)
            .remove(SP_KEY_ACCESS_TOKEN)
            .remove(SP_KEY_REFRESH_TOKEN)
            .remove(SP_KEY_EXPIRE_AT)
            .apply()
    }

    private fun clearAndNull(): SavedSession? {
        clear()
        return null
    }

    private fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(iv.size + ct.size).also {
            System.arraycopy(iv, 0, it, 0, iv.size)
            System.arraycopy(ct, 0, it, iv.size, ct.size)
        }
        Base64.encodeToString(blob, Base64.NO_WRAP)
    }.onFailure { Log.w(TAG, "token encrypt failed", it) }.getOrNull()

    private fun decrypt(encoded: String): String? = runCatching {
        val blob = Base64.decode(encoded, Base64.NO_WRAP)
        if (blob.size <= GCM_IV_LENGTH) return null
        val iv = blob.copyOfRange(0, GCM_IV_LENGTH)
        val ct = blob.copyOfRange(GCM_IV_LENGTH, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.onFailure { Log.w(TAG, "token decrypt failed", it) }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }
}
