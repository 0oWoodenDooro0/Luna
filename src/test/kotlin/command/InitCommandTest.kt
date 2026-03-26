package website.woodendoor.command

import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.User
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import io.mockk.*
import kotlinx.coroutines.runBlocking
import website.woodendoor.PlayerService
import kotlin.test.*

class InitCommandTest {

    private val command = InitCommand()
    private val interaction = mockk<ChatInputCommandInteraction>(relaxed = true)
    private val user = mockk<User>()
    private val userId = Snowflake(123456789)

    @BeforeTest
    fun setup() {
        mockkObject(PlayerService)
        every { interaction.user } returns user
        every { user.id } returns userId
    }

    @AfterTest
    fun cleanup() {
        unmockkAll()
    }

    @Test
    fun `test handle when player is not registered`() = runBlocking {
        every { PlayerService.registerPlayer(any()) } returns false
        
        command.handle(interaction)

        verify { PlayerService.registerPlayer("123456789") }
    }

    @Test
    fun `test handle when player registration succeeds`() = runBlocking {
        every { PlayerService.registerPlayer(any()) } returns true
        
        command.handle(interaction)

        verify { PlayerService.registerPlayer("123456789") }
    }
}
