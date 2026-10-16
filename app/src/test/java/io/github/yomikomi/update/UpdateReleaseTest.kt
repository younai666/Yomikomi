package io.github.yomikomi.update

import org.junit.Assert.*
import org.junit.Test

class UpdateReleaseTest {
    private fun version(value: String) = AppVersion.parse(value)!!
    private fun asset(name: String) = UpdateAsset(1, name,
        "https://github.com/younai666/Yomikomi/releases/download/v0.1.2/$name", 1024, "a".repeat(64))

    @Test fun numericVersionsAreNotLexicographicAndIgnoreBuildMetadata() {
        assertTrue(version("v0.1.10") > version("0.1.9"))
        assertTrue(version("1.0.0") > version("0.99.999"))
        assertEquals(0, version("v0.1.1+build.7").compareTo(version("0.1.1+other")))
        assertEquals(0, version("V1.2.3").compareTo(version("1.2.3")))
    }

    @Test fun prereleasePrecedenceFollowsSemver() {
        val order = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0")
        order.zipWithNext().forEach { (a, b) -> assertTrue("$a < $b", version(a) < version(b)) }
    }

    @Test fun malformedOrUnboundedVersionTagsAreNotComparable() {
        listOf("", "latest", "1.2", "01.2.3", "1.02.3", "1.2.03", "1.2.3-01", "1.2.3-", "v1.2.3\n", "1".repeat(129))
            .forEach { assertNull(it, AppVersion.parse(it)) }
    }

    @Test fun selectsDeviceAbiBeforeUniversalAndExcludesOtherBuilds() {
        val universal = asset("Yomikomi-0.1.2.apk")
        val arm = asset("Yomikomi-0.1.2-arm64-v8a.apk")
        val x86 = asset("Yomikomi-0.1.2-x86_64.apk")
        val release = UpdateRelease(version("0.1.2"), listOf(asset("Yomikomi-debug.apk"), x86, universal, arm))
        assertEquals(arm, release.apk(listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(x86, release.apk(listOf("x86_64", "x86")))
        assertEquals(universal, release.apk(listOf("armeabi-v7a")))
        assertNull(UpdateRelease(version("0.1.2"), listOf(x86, asset("app-release-unsigned.apk"))).apk(listOf("arm64-v8a")))
    }

    @Test fun downloadedAssetMustBeBoundedHashedAndFromThisRepository() {
        val good = asset("Yomikomi-0.1.2.apk")
        assertTrue(good.valid())
        listOf(good.copy(bytes = 0), good.copy(bytes = 268_435_457), good.copy(sha256 = ""), good.copy(id = -1),
            good.copy(url = good.url.replace("https:", "http:")),
            good.copy(url = good.url.replace("github.com", "github.com.evil.test")),
            good.copy(url = good.url.replace("younai666", "another-user")),
            good.copy(url = good.url.replace("github.com", "secret@github.com")),
            good.copy(url = good.url + "?token=private")).forEach { assertFalse(it.valid()) }
    }
}
