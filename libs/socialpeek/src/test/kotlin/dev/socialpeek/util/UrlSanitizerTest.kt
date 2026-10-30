package dev.socialpeek.util

import dev.socialpeek.model.Platform
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UrlSanitizerTest {

    @Test
    fun `1 X (Twitter) tests`() {
        // s and t params removed
        val clean1 = UrlSanitizer.clean("https://x.com/user/status/123456?s=20&t=abc")
        assertEquals("https://x.com/user/status/123456", clean1)

        // ref_src param removed
        val clean2 = UrlSanitizer.clean("https://twitter.com/user/status/123456?ref_src=twsrc%5Etfw")
        assertEquals("https://twitter.com/user/status/123456", clean2)

        assertTrue(UrlSanitizer.hasTrackingParams("https://x.com/user/status/123456?s=20&t=abc"))
        assertTrue(UrlSanitizer.hasTrackingParams("https://twitter.com/user/status/123456?ref_src=twsrc%5Etfw"))
        assertFalse(UrlSanitizer.hasTrackingParams("https://x.com/user/status/123456"))
    }

    @Test
    fun `2 Instagram and Threads tests`() {
        // Instagram igsh removed, trailing slash preserved
        val cleanIg = UrlSanitizer.clean("https://www.instagram.com/p/C123456/?igsh=MzRlODBiNWFlZA==")
        assertEquals("https://www.instagram.com/p/C123456/", cleanIg)

        // Threads xmt removed
        val cleanThreads = UrlSanitizer.clean("https://www.threads.net/@user/post/C123456?xmt=AQG123")
        assertEquals("https://www.threads.net/@user/post/C123456", cleanThreads)

        assertTrue(UrlSanitizer.hasTrackingParams("https://www.instagram.com/p/C123456/?igsh=MzRlODBiNWFlZA=="))
        assertTrue(UrlSanitizer.hasTrackingParams("https://www.threads.net/@user/post/C123456?xmt=AQG123"))
        assertFalse(UrlSanitizer.hasTrackingParams("https://www.instagram.com/p/C123456/"))
        assertFalse(UrlSanitizer.hasTrackingParams("https://www.threads.net/@user/post/C123456"))
    }

    @Test
    fun `3 Bilibili functional parameter preservation tests`() {
        // spm_id_from and vd_source removed, p=3 and t=120 MUST be preserved
        val raw = "https://www.bilibili.com/video/BV1xx411c7mD?spm_id_from=333.1007&vd_source=abc&p=3&t=120"
        val clean = UrlSanitizer.clean(raw)
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD?p=3&t=120", clean)

        assertTrue(UrlSanitizer.hasTrackingParams(raw))
        // When only p and t remain, hasTrackingParams should be false
        assertFalse(UrlSanitizer.hasTrackingParams(clean))
    }

    @Test
    fun `4 YouTube functional parameter preservation tests`() {
        // youtu.be short link: si removed, t=45s preserved
        val rawShort = "https://youtu.be/dQw4w9WgXcQ?si=abc123xyz&t=45s"
        val cleanShort = UrlSanitizer.clean(rawShort)
        assertEquals("https://youtu.be/dQw4w9WgXcQ?t=45s", cleanShort)

        // youtube.com watch link: feature removed, v, list, index preserved
        val rawWatch = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL123&index=2&feature=shared"
        val cleanWatch = UrlSanitizer.clean(rawWatch)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL123&index=2", cleanWatch)

        assertTrue(UrlSanitizer.hasTrackingParams(rawShort))
        assertTrue(UrlSanitizer.hasTrackingParams(rawWatch))
        assertFalse(UrlSanitizer.hasTrackingParams(cleanShort))
        assertFalse(UrlSanitizer.hasTrackingParams(cleanWatch))
    }

    @Test
    fun `5 Boundary and edge cases`() {
        // Pure URL without params should remain untouched without trailing '?'
        val noParams = "https://x.com/user/status/123456"
        assertEquals(noParams, UrlSanitizer.clean(noParams))
        assertFalse(UrlSanitizer.hasTrackingParams(noParams))

        val noParamsIg = "https://www.instagram.com/p/C123456/"
        assertEquals(noParamsIg, UrlSanitizer.clean(noParamsIg))

        // Trailing '?' should be removed
        val trailingQuestion = "https://x.com/user/status/123456?"
        assertEquals("https://x.com/user/status/123456", UrlSanitizer.clean(trailingQuestion))

        // Empty parameters
        val emptyParams = "https://x.com/user/status/123456?&&"
        assertEquals("https://x.com/user/status/123456", UrlSanitizer.clean(emptyParams))

        // Idempotency: clean(clean(url)) == clean(url)
        val sample = "https://www.bilibili.com/video/BV1xx411c7mD?spm_id_from=333.1007&vd_source=abc&p=3&t=120"
        val once = UrlSanitizer.clean(sample)
        val twice = UrlSanitizer.clean(once)
        assertEquals(once, twice)

        // Case-insensitivity for tracking parameters
        val uppercaseTracking = "https://x.com/user/status/123456?S=20&T=ABC&UTM_SOURCE=TWITTER"
        assertEquals("https://x.com/user/status/123456", UrlSanitizer.clean(uppercaseTracking))

        // URL without scheme should be canonicalized to https
        assertEquals("https://www.instagram.com/p/C123456/", UrlSanitizer.clean("instagram.com/p/C123456/?igsh=123"))
    }

    @Test
    fun `6 Reddit post and context parameter tests`() {
        // utm_source removed, post slug normalized
        val rawReddit = "https://www.reddit.com/r/sub/comments/id/title/?utm_source=share"
        val cleanReddit = UrlSanitizer.clean(rawReddit)
        assertEquals("https://www.reddit.com/r/sub/comments/id/", cleanReddit)

        // context parameter preserved on Reddit
        val rawRedditContext = "https://www.reddit.com/r/sub/comments/id/title/?utm_source=share&context=3"
        val cleanRedditContext = UrlSanitizer.clean(rawRedditContext)
        assertEquals("https://www.reddit.com/r/sub/comments/id/?context=3", cleanRedditContext)

        assertTrue(UrlSanitizer.hasTrackingParams(rawReddit))
        assertFalse(UrlSanitizer.hasTrackingParams("https://www.reddit.com/r/sub/comments/id/?context=3", Platform.REDDIT))
    }

    @Test
    fun `7 Instagram img_index whitelist preservation`() {
        val rawIgWithIndex = "https://www.instagram.com/p/C123456/?igsh=MzRlODBiNWFlZA==&img_index=2"
        val cleanIgWithIndex = UrlSanitizer.clean(rawIgWithIndex)
        assertEquals("https://www.instagram.com/p/C123456/?img_index=2", cleanIgWithIndex)
    }

    @Test
    fun `8 Facebook tracking removal and functional parameter preservation`() {
        // fbclid and mibextid removed, host normalized to www.facebook.com
        val rawPost = "https://facebook.com/zuck/posts/pfbid123?fbclid=IwAR123&mibextid=ZbWKwL"
        val cleanPost = UrlSanitizer.clean(rawPost)
        assertEquals("https://www.facebook.com/zuck/posts/pfbid123/", cleanPost)

        // Reel with share_url removed and trailing slash preserved
        val rawReel = "https://www.facebook.com/reel/832727859062012/?share_url=https%3A%2F%2Fwww.facebook.com%2Fshare%2Fv%2F18L6FcWfXE%2F%3Fmibextid%3DwwXIfr"
        val cleanReel = UrlSanitizer.clean(rawReel)
        assertEquals("https://www.facebook.com/reel/832727859062012/", cleanReel)
        assertTrue(UrlSanitizer.hasTrackingParams(rawReel))
        assertFalse(UrlSanitizer.hasTrackingParams(cleanReel))

        // Photo with fbid preserved and tracking parameters removed
        val rawPhoto = "https://www.facebook.com/photo.php?fbid=10153231379946729&set=a.123&__tn__=%2CO*F"
        val cleanPhoto = UrlSanitizer.clean(rawPhoto)
        assertEquals("https://www.facebook.com/photo.php?fbid=10153231379946729&set=a.123", cleanPhoto)

        // Watch with v preserved and tracking removed
        val rawWatch = "https://www.facebook.com/watch/?v=10153231379946729&ref=sharing&mibextid=wwXIfr"
        val cleanWatch = UrlSanitizer.clean(rawWatch)
        assertEquals("https://www.facebook.com/watch/?v=10153231379946729", cleanWatch)

        // Permalink with story_fbid and id preserved, tracking removed
        val rawPermalink = "https://m.facebook.com/permalink.php?story_fbid=12345&id=67890&substory_index=0&notif_t=feedback_reaction_generic"
        val cleanPermalink = UrlSanitizer.clean(rawPermalink)
        assertEquals("https://www.facebook.com/permalink.php?story_fbid=12345&id=67890", cleanPermalink)

        assertTrue(UrlSanitizer.hasTrackingParams(rawPost))
        assertTrue(UrlSanitizer.hasTrackingParams(rawPhoto))
        assertFalse(UrlSanitizer.hasTrackingParams(cleanPost))
        assertFalse(UrlSanitizer.hasTrackingParams(cleanPhoto))
    }
}
