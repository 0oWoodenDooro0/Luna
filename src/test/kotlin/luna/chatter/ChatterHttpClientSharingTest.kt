package luna.chatter

import dev.kord.core.Kord
import dev.kord.core.event.Event
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import luna.config.EnvReader
import luna.plugin.DefaultPluginContext
import org.junit.jupiter.api.Test
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatterHttpClientSharingTest {
    @Test
    fun `ChatterPlugin creates one shared HttpClient pool and closes it cleanly on shutdown`() =
        runTest {
            val mockKord = mockk<Kord>(relaxed = true)
            every { mockKord.coroutineContext } returns EmptyCoroutineContext
            every { mockKord.events } returns MutableSharedFlow<Event>()

            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = mockk(relaxed = true),
                    env =
                        EnvReader.of(
                            mapOf(
                                "GEMINI_API_KEY" to "test-gemini-key",
                                "CHATTER_EMOJI_VISION_MODEL" to "gemini-2.5-flash",
                            ),
                        ),
                )

            val plugin = ChatterPlugin()
            plugin.initialize(context)

            val sharedClient = assertNotNull(plugin.sharedHttpClient, "ChatterPlugin must initialize a sharedHttpClient")
            assertTrue(sharedClient.isActive, "sharedHttpClient must be active after initialize")

            plugin.decisionClient?.close()
            plugin.chatterGenerator?.close()
            assertTrue(
                sharedClient.isActive,
                "Closing child clients must not close ChatterPlugin's sharedHttpClient",
            )

            plugin.shutdown()
            assertNull(plugin.sharedHttpClient, "sharedHttpClient reference must be cleared on shutdown")
            assertFalse(sharedClient.isActive, "sharedHttpClient must be closed on ChatterPlugin shutdown")
        }
}
