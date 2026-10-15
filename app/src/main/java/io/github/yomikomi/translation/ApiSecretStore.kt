package io.github.yomikomi.translation

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The key never enters ordinary settings or backups, and is bound to its HTTPS origin. */
class ApiSecretStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "api-key"))

    fun hasKey(endpoint: String): Boolean = file.baseFile.exists() &&
        file.openRead().bufferedReader().use { it.readLine() } == runCatching { ApiSettings.origin(endpoint) }.getOrNull()

    fun read(endpoint: String): String {
        check(hasKey(endpoint)) { "请为此接口填写 API 密钥" }
        try {
            val lines = file.openRead().bufferedReader().use { it.readLines() }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyStore().getKey(ALIAS, null) as SecretKey,
                GCMParameterSpec(128, Base64.decode(lines[1], Base64.NO_WRAP)))
            cipher.updateAAD(lines[0].toByteArray(Charsets.UTF_8))
            return cipher.doFinal(Base64.decode(lines[2], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (error: Exception) { throw IllegalStateException("API 密钥不可用，请重新填写", error) }
    }

    fun save(endpoint: String, secret: String) {
        require(secret.isNotBlank() && '\r' !in secret && '\n' !in secret) { "API 密钥格式无效" }
        val origin = ApiSettings.origin(endpoint)
        val stream = file.startWrite()
        try {
            val store = keyStore()
            val key = (store.getKey(ALIAS, null) as SecretKey?) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(origin.toByteArray(Charsets.UTF_8))
            val content = listOf(origin, Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                Base64.encodeToString(cipher.doFinal(secret.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)).joinToString("\n")
            stream.write(content.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw IllegalStateException("无法保存 API 密钥", error)
        }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private companion object { const val ALIAS = "yomikomi-api-key" }
}
