package app.firetvhome

import org.junit.Assert.assertEquals
import org.junit.Test

class BgDecodeTest {
    @Test fun decodeSize() {
        assertEquals(2, BgDecode.sampleSize(1920, 1080, 1920, 1080))
        assertEquals(2, BgDecode.sampleSize(1920, 1080, 1280, 720))
        assertEquals(1, BgDecode.sampleSize(1280, 720, 1920, 1080))
        assertEquals(4, BgDecode.sampleSize(3840, 2160, 1920, 1080))
        assertEquals(1, BgDecode.sampleSize(0, 0, 1920, 1080))
        assertEquals(960L * 540 * 2, BgDecode.rgb565Bytes(1920, 1080, 2))
    }
}
