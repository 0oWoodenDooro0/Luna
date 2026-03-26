package website.woodendoor.command

import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.User
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import io.mockk.*
import kotlinx.coroutines.runBlocking
import website.woodendoor.HuntService
import website.woodendoor.LevelingService
import kotlin.test.*

class HuntCommandTest {

    private val command = HuntCommand()
    private val interaction = mockk<ChatInputCommandInteraction>(relaxed = true)
    private val user = mockk<User>()
    private val userId = Snowflake(123456789)

    @BeforeTest
    fun setup() {
        mockkObject(HuntService)
        every { interaction.user } returns user
        every { user.id } returns userId
    }

    @AfterTest
    fun cleanup() {
        unmockkAll()
    }

    @Test
    fun `test handle calls HuntService`() = runBlocking {
        val levelUpResult = LevelingService.LevelUpResult(1, 20, false)
        coEvery { HuntService.processHunt(any()) } returns HuntService.HuntResult.Success(10, levelUpResult)

        command.handle(interaction)

        coVerify { HuntService.processHunt("123456789") }
    }
}
