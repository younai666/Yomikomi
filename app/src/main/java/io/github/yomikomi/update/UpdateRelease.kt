package io.github.yomikomi.update

import java.math.BigInteger
import java.net.URI

/** Semantic versions, including numeric prerelease identifiers; build metadata does not change precedence. */
@ConsistentCopyVisibility
data class AppVersion private constructor(private val parts: List<BigInteger>, private val preview: List<String>) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        parts.indices.forEach { index -> parts[index].compareTo(other.parts[index]).let { if (it != 0) return it } }
        if (preview.isEmpty() || other.preview.isEmpty()) return when {
            preview.isEmpty() && other.preview.isEmpty() -> 0
            preview.isEmpty() -> 1
            else -> -1
        }
        for (index in 0 until minOf(preview.size, other.preview.size)) {
            val a = preview[index]; val b = other.preview[index]
            val numericA = a.all(Char::isDigit); val numericB = b.all(Char::isDigit)
            val compared = when {
                numericA && numericB -> a.toBigInteger().compareTo(b.toBigInteger())
                numericA -> -1
                numericB -> 1
                else -> a.compareTo(b)
            }
            if (compared != 0) return compared
        }
        return preview.size.compareTo(other.preview.size)
    }

    companion object {
        private val pattern = Regex("[vV]?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")
        fun parse(text: String): AppVersion? {
            if (text.length > 128) return null
            val match = pattern.matchEntire(text) ?: return null
            val preview = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            if (preview.any { it.length > 1 && it.all(Char::isDigit) && it.startsWith('0') }) return null
            return AppVersion((1..3).map { match.groupValues[it].toBigInteger() }, preview)
        }
    }
}

data class UpdateAsset(val id: Long, val name: String, val url: String, val bytes: Long, val sha256: String) {
    val fileName get() = "$sha256.apk"
    fun valid(): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return id > 0 && bytes in 1..268_435_456 && Regex("[0-9a-f]{64}").matches(sha256) &&
            uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.path.startsWith("/younai666/Yomikomi/releases/download/")
    }
}

data class UpdateRelease(val version: AppVersion, val assets: List<UpdateAsset>) {
    fun apk(abis: List<String>): UpdateAsset? {
        val known = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        return assets.mapNotNull { asset ->
            val name = asset.name.lowercase(java.util.Locale.ROOT)
            if (!name.endsWith(".apk") || listOf("debug", "unsigned", "androidtest").any(name::contains)) return@mapNotNull null
            if (!name.startsWith("yomikomi") && name != "app-release.apk") return@mapNotNull null
            val abi = known.firstOrNull(name::contains)
            val rank = if (abi == null) abis.size else abis.indexOf(abi).takeIf { it >= 0 } ?: return@mapNotNull null
            asset to rank
        }.minByOrNull { it.second }?.first
    }
}

class UpdateException(val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause)
