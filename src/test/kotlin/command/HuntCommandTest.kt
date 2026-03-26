package website.woodendoor.command

import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.User
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import website.woodendoor.repository.PlayerData
import website.woodendoor.repository.PlayerRepository
import website.woodendoor.LevelingService
import website.woodendoor.Players
import java.io.File
import kotlin.test.*

class HuntCommandTest {

    private val testDbFile = "hunt_test.db"

    @BeforeTest
    fun setup() {
        Database.connect("jdbc:sqlite:$testDbFile", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.drop(Players)
            SchemaUtils.create(Players)
        }
        mockkObject(PlayerRepository)
    }

    @AfterTest
    fun cleanup() {
        unmockkObject(PlayerRepository)
        File(testDbFile).delete()
    }

    @Test
    fun `test hunt awards xp`() = runBlocking {
        val interaction = mockk<ChatInputCommandInteraction>(relaxed = true)
        val user = mockk<User>()
        val userId = Snowflake(123L)
        val userIdString = userId.toString()

        every { interaction.user } returns user
        every { user.id } returns userId

        val initialPlayerData = PlayerData(userIdString, 1, 0, 100, 0L)
        every { PlayerRepository.getPlayer(userIdString) } returns initialPlayerData

        // Prepare the mock results for updates
        every { PlayerRepository.addXp(userIdString, 20) } returns LevelingService.LevelUpResult(1, 20, false)
        every { PlayerRepository.updateHuntResult(any(), any(), any()) } just Runs

        val command = HuntCommand()
        command.handle(interaction)

        // Verify addXp was called
        verify { PlayerRepository.addXp(userIdString, 20) }
    }
}
