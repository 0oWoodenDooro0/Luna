package luna.rpg

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class RpgConfigReloadTest {
    private val configPath = "config.yml"

    @Test
    fun testReloadConfig() {
        val configFile = File(configPath)
        
        // Ensure default config exists
        RpgConfig.reload()
        val originalFloorSize = RpgConfig.Exploration.FLOOR_SIZE
        
        // Modify file
        configFile.writeText(configFile.readText().replace("floor_size: $originalFloorSize", "floor_size: ${originalFloorSize + 10}"))
        
        // Reload
        RpgConfig.reload()
        
        assertEquals(originalFloorSize + 10, RpgConfig.Exploration.FLOOR_SIZE)
        
        // Reset to original
        configFile.writeText(configFile.readText().replace("floor_size: ${originalFloorSize + 10}", "floor_size: $originalFloorSize"))
        RpgConfig.reload()
    }
}
