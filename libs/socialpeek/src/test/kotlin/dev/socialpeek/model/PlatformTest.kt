package dev.socialpeek.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlatformTest {

    @Test
    fun `test platform metadata values`() {
        assertEquals(0x1877F2, Platform.FACEBOOK.brandColorHex)
        assertNull(Platform.FACEBOOK.defaultIconUrl)

        assertEquals(0x00AEEC, Platform.BILIBILI.brandColorHex)
        assertNull(Platform.BILIBILI.defaultIconUrl)

        assertEquals(0x1DA1F2, Platform.X.brandColorHex)
        assertNull(Platform.X.defaultIconUrl)

        assertEquals(0xE1306C, Platform.INSTAGRAM.brandColorHex)
        assertNull(Platform.INSTAGRAM.defaultIconUrl)

        assertEquals(0x000000, Platform.THREADS.brandColorHex)
        assertNull(Platform.THREADS.defaultIconUrl)

        assertEquals(0xFF0000, Platform.YOUTUBE.brandColorHex)
        assertNull(Platform.YOUTUBE.defaultIconUrl)

        assertEquals(0xFF4500, Platform.REDDIT.brandColorHex)
        assertEquals(
            "https://www.redditstatic.com/shreddit/assets/favicon/192x192.png",
            Platform.REDDIT.defaultIconUrl
        )

        assertEquals(0x5865F2, Platform.GENERIC.brandColorHex)
        assertNull(Platform.GENERIC.defaultIconUrl)
    }
}
