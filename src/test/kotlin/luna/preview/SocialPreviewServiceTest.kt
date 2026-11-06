package luna.preview

import dev.socialpeek.SocialPeek
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SocialPreviewServiceTest {
    @Test
    fun `canResolveSocial strictly excludes non-social and generic web URLs`() {
        val nonSocialUrls =
            listOf(
                "https://example.com",
                "https://example.com/article/123",
                "https://github.com/0oWoodenDooro0/Luna",
                "https://news.ycombinator.com/item?id=12345",
                "https://en.wikipedia.org/wiki/Kotlin_(programming_language)",
                "https://blog.google/technology/ai/",
                "http://localhost:8080/test",
            )

        for (url in nonSocialUrls) {
            // General resolution (for summarizer / text extraction) may match GenericWebResolver
            assertTrue(
                SocialPeek.canResolve(url),
                "Expected generic SocialPeek.canResolve to resolve non-social URL: $url",
            )

            // Social preview auto-previewing MUST NOT resolve generic non-social URLs
            assertFalse(
                SocialPeek.canResolveSocial(url),
                "SocialPeek.canResolveSocial must reject non-social URL to prevent auto-preview scope creep: $url",
            )
        }
    }

    @Test
    fun `canResolveSocial recognizes dedicated social platform URLs`() {
        val socialUrls =
            listOf(
                "https://x.com/Kotlin/status/1800000000000000000",
                "https://twitter.com/Kotlin/status/1800000000000000000",
                "https://www.reddit.com/r/Kotlin/comments/12345/discussion/",
                "https://www.instagram.com/p/C-example123/",
                "https://www.threads.net/@zuck/post/C-example123",
                "https://www.bilibili.com/video/BV1xx411c7mD",
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                "https://youtu.be/dQw4w9WgXcQ",
            )

        for (url in socialUrls) {
            assertTrue(
                SocialPeek.canResolve(url),
                "Expected SocialPeek.canResolve to match social URL: $url",
            )
            assertTrue(
                SocialPeek.canResolveSocial(url),
                "Expected SocialPeek.canResolveSocial to accept social URL: $url",
            )
        }
    }

    @org.junit.jupiter.api.BeforeEach
    fun setUp() {
        luna.core.LunaWebhookRegistry.clear()
        SocialPreviewService.clearWebhookCache()
    }

    @Test
    fun `getOrCreateWebhook registers existing webhook into LunaWebhookRegistry`() =
        kotlinx.coroutines.runBlocking {
            val kordMock = io.mockk.mockk<dev.kord.core.Kord>()
            val selfId =
                dev.kord.common.entity
                    .Snowflake(42L)
            io.mockk.every { kordMock.selfId } returns selfId

            val channelMock = io.mockk.mockk<dev.kord.core.behavior.channel.CategorizableChannelBehavior>()
            val channelId =
                dev.kord.common.entity
                    .Snowflake(1001L)
            io.mockk.every { channelMock.id } returns channelId

            val existingWebhook = io.mockk.mockk<dev.kord.core.entity.Webhook>()
            val webhookId =
                dev.kord.common.entity
                    .Snowflake(555L)
            io.mockk.every { existingWebhook.id } returns webhookId
            io.mockk.every { existingWebhook.creatorId } returns selfId
            io.mockk.every { existingWebhook.name } returns "Luna Preview"
            io.mockk.every { existingWebhook.token } returns "token123"

            io.mockk.every { channelMock.webhooks } returns kotlinx.coroutines.flow.flowOf(existingWebhook)

            val pair = SocialPreviewService.getOrCreateWebhook(channelMock, kordMock)
            kotlin.test.assertNotNull(pair)
            kotlin.test.assertEquals(webhookId, pair.first.id)
            kotlin.test.assertTrue(luna.core.LunaWebhookRegistry.contains(webhookId))
        }

    @Test
    fun `getOrCreateWebhook registers cached webhook into LunaWebhookRegistry`() =
        kotlinx.coroutines.runBlocking {
            val kordMock = io.mockk.mockk<dev.kord.core.Kord>()
            val resourcesMock = io.mockk.mockk<dev.kord.core.ClientResources>()
            val strategyMock = io.mockk.mockk<dev.kord.core.supplier.EntitySupplyStrategy<*>>()
            io.mockk.every { resourcesMock.defaultStrategy } returns strategyMock
            io.mockk.every { kordMock.resources } returns resourcesMock

            val selfId =
                dev.kord.common.entity
                    .Snowflake(42L)
            io.mockk.every { kordMock.selfId } returns selfId

            val channelMock = io.mockk.mockk<dev.kord.core.behavior.channel.CategorizableChannelBehavior>()
            val channelId =
                dev.kord.common.entity
                    .Snowflake(1003L)
            val webhookId =
                dev.kord.common.entity
                    .Snowflake(888L)
            val token = "tokenCached"

            io.mockk.every { channelMock.id } returns channelId

            val existingWebhook = io.mockk.mockk<dev.kord.core.entity.Webhook>()
            io.mockk.every { existingWebhook.id } returns webhookId
            io.mockk.every { existingWebhook.creatorId } returns selfId
            io.mockk.every { existingWebhook.name } returns "Luna Preview"
            io.mockk.every { existingWebhook.token } returns token

            io.mockk.every { channelMock.webhooks } returns kotlinx.coroutines.flow.flowOf(existingWebhook)

            // First call populates cache and registers
            val pair1 = SocialPreviewService.getOrCreateWebhook(channelMock, kordMock)
            kotlin.test.assertNotNull(pair1)

            // Clear registry to verify cached retrieval re-registers it
            luna.core.LunaWebhookRegistry.clear()
            kotlin.test.assertFalse(luna.core.LunaWebhookRegistry.contains(webhookId))

            val webhookMock = io.mockk.mockk<dev.kord.core.entity.Webhook>()
            io.mockk.coEvery { kordMock.getWebhookWithTokenOrNull(webhookId, token) } returns webhookMock

            val pair2 = SocialPreviewService.getOrCreateWebhook(channelMock, kordMock)
            kotlin.test.assertNotNull(pair2)
            kotlin.test.assertEquals(webhookMock, pair2.first)
            kotlin.test.assertTrue(luna.core.LunaWebhookRegistry.contains(webhookId))
        }
}
