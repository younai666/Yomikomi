package io.github.yomikomi.capture

import io.github.yomikomi.translation.PixelBounds
import org.junit.Assert.*
import org.junit.Test

class CaptureInvalidationTest {
    @Test fun imePopupAndDuplicateWindowEventsDoNotCancelSlowLoading() {
        assertNull(windowCancellation("reader", 8, "keyboard", 9, false))
        assertNull(windowCancellation("reader", 8, "reader", 10, false))
        assertNull(windowCancellation("reader", 8, "reader", 8, true))
        assertNull(windowCancellation("reader", 8, "reader", 10, true, busy = true))
        assertNull(windowCancellation("", -1, "reader", 8, true))
        assertNull(windowCancellation("reader", -1, "reader", 8, true))
    }

    @Test fun confirmedApplicationAndWindowSwitchStillCancel() {
        assertEquals(CaptureCancelReason.APP_SWITCH, windowCancellation("reader", 8, "other", 9, true))
        assertEquals(CaptureCancelReason.APP_SWITCH, windowCancellation("reader", 8, "other", 9, true, busy = true))
        assertEquals(CaptureCancelReason.WINDOW_CHANGED, windowCancellation("reader", 8, "reader", 9, true))
    }

    private val viewport = PixelBounds(0, 100, 1080, 2300)
    private val regions = (0..2300 step 180).map { PixelBounds(50, it, 700, it + 25) }
    private fun fingerprint(pixel: (Int, Int) -> Int) = ScreenshotFingerprint.sample(viewport, regions, pixel)

    @Test fun pixelValidationIgnoresMinorRenderingDifferencesAndSystemBars() {
        val first = fingerprint { _, _ -> 0x202020 }
        assertFalse(first.compare(fingerprint { _, _ -> 0x252525 }).changed)
        assertFalse(first.compare(fingerprint { x, y -> if (x in 500..505 && y in 1100..1130) 0xffffff else 0x202020 }).changed)
        assertFalse(first.compare(fingerprint { _, y -> if (y < 100 || y >= 2300) 0xffffff else 0x202020 }).changed)
    }

    @Test fun pixelValidationDetectsReplacedPageScrollingAndGeometryWithoutOcr() {
        val first = fingerprint { x, y -> if (x in 50..700 && y % 180 in 0..25) 0xffffff else 0x202020 }
        assertFalse(first.compare(fingerprint { x, y -> if (x in 50..700 && y % 180 in 0..25) 0xffffff else 0x202020 }).changed)
        assertTrue(first.compare(fingerprint { _, _ -> 0x202020 }).changed)
        assertTrue(first.compare(fingerprint { x, y -> if (x in 50..700 && (y + 60) % 180 in 0..25) 0xffffff else 0x202020 }).changed)
        assertTrue(first.compare(ScreenshotFingerprint.sample(PixelBounds(0, 100, 1080, 2200)) { _, _ -> 0x202020 }).changed)
    }

    @Test fun unrelatedAnimationAndLocalizedRefreshDoNotInvalidateCapturedText() {
        val first = fingerprint { _, _ -> 0x202020 }
        assertFalse(first.compare(fingerprint { x, _ -> if (x > 720) 0xffffff else 0x202020 }).changed)
        assertFalse(first.compare(fingerprint { x, y -> if (x in 50..100 && y in 180..205) 0xffffff else 0x202020 }).changed)
        assertTrue(first.compare(fingerprint { x, y -> if (x in 50..700 && y < 1200) 0xffffff else 0x202020 }).changed)
    }
}
