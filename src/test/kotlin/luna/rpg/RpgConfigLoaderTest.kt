package luna.rpg

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RpgConfigLoaderTest {
    private val configPath = "config_test.yml"

    @Test
    fun testGenerateDefaultConfig() {
        val configFile = File(configPath)
        if (configFile.exists()) configFile.delete()

        val loader = RpgConfigLoader(configPath)
        loader.load()

        assertTrue(configFile.exists(), "Config file should be generated if not exists")
        
        // Cleanup
        configFile.delete()
    }

    @Test
    fun testLoadCustomConfig() {
        val configFile = File(configPath)
        configFile.writeText("""
            exploration:
              floor_size: 10
        """.trimIndent())

        val loader = RpgConfigLoader(configPath)
        val config = loader.load()

        assertEquals(10, config.exploration.floorSize)
        
        // Cleanup
        configFile.delete()
    }
}
