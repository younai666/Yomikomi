package io.github.yomikomi.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import io.github.yomikomi.logging.LogModule
import io.github.yomikomi.logging.RunLog
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

object ApkInstaller {
    @Suppress("DEPRECATION")
    private val flags get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    fun installed(context: Context): PackageInfo = context.packageManager.getPackageInfo(context.packageName, flags)

    @Suppress("DEPRECATION")
    fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    @Suppress("DEPRECATION")
    fun verify(context: Context, apk: File, expected: AppVersion) = RunLog.measure(LogModule.UPDATE, "verify_apk") {
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw UpdateException("安装包无效")
        val current = installed(context)
        if (archive.packageName != context.packageName) throw UpdateException("安装包不属于 Yomikomi")
        if (AppVersion.parse(archive.versionName.orEmpty())?.compareTo(expected) != 0 || versionCode(archive) <= versionCode(current))
            throw UpdateException("安装包版本不匹配")
        val expectedSigners = signers(current)
        if (expectedSigners.isEmpty() || signers(archive) != expectedSigners) throw UpdateException("安装包签名不匹配")
        ZipFile(apk).use { zip ->
            val abis = zip.entries().asSequence().filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                .map { it.name.split('/')[1] }.toSet()
            if (abis.isNotEmpty() && Build.SUPPORTED_ABIS.none { it in abis }) throw UpdateException("安装包不支持此设备")
        }
        RunLog.info(LogModule.UPDATE, "apk_verified", "version_code" to versionCode(archive))
    }

    fun allowed(context: Context) = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun intent(context: Context, apk: File): Intent {
        check(apk.isFile)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("Yomikomi", uri) }
    }
}
