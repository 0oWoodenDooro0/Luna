package luna.rpg

import luna.rpg.repository.PlayerRepository
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExplorationScalingTest {
    private val configPath = "config_scaling_test.yml"

    @BeforeTest
    fun setup() {
        val configFile = File(configPath)
        if (configFile.exists()) configFile.delete()
    }

    @AfterTest
    fun cleanup() {
        val configFile = File(configPath)
        if (configFile.exists()) configFile.delete()
    }

    @Test
    fun testLoadResourceScalePerFloor() {
        val configFile = File(configPath)
        configFile.writeText(
            """
            exploration:
              resource_scale_per_floor: 15
            """.trimIndent(),
        )

        val loader = RpgConfigLoader(configPath)
        val config = loader.load()

        // This should fail to compile if I haven't added the property yet.
        // Or fail at runtime if I haven't updated the loader.
        assertEquals(15, config.exploration.resourceScalePerFloor)
    }

    @Test
    fun testRpgConfigExposeScale() {
        val configFile = File(configPath)
        configFile.writeText(
            """
            exploration:
              resource_scale_per_floor: 20
            """.trimIndent(),
        )

        RpgConfig.configPath = configPath
        RpgConfig.reload()

        assertEquals(20, RpgConfig.Exploration.RESOURCE_SCALE_PER_FLOOR)
    }

    @Test
    fun testScalingLogicFloor1() {
        // Mock config for consistent testing
        // baseMin=1, baseMax=5, scale=10
        // floor 1: min = 1 + (1-1)*10 = 1, max = 5 + (1-1)*10 = 5
        for (i in 1..100) {
            val amount = PlayerRepository.calculateExplorationReward(1)
            assertTrue(amount in 1..5, "Floor 1 amount $amount should be in 1..5")
        }
    }

    @Test
    fun testScalingLogicFloor10() {
        // floor 10: min = 1 + (10-1)*10 = 91, max = 5 + (10-1)*10 = 95
        for (i in 1..100) {
            val amount = PlayerRepository.calculateExplorationReward(10)
            assertTrue(amount in 91..95, "Floor 10 amount $amount should be in 91..95")
        }
    }
}
