package luna.rpg

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
