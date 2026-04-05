package luna.rpg

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class RpgConfigReloadTest {
    private val configPath = "config_reload_test.yml"

    @Test
    fun testReloadConfig() {
        val configFile = File(configPath)
        if (configFile.exists()) configFile.delete()

        // Set test config path
        RpgConfig.configPath = configPath
        
        val originalFloorSize = RpgConfig.Exploration.FLOOR_SIZE
        
        // Modify file
        configFile.writeText(configFile.readText().replace("floor_size: $originalFloorSize", "floor_size: ${originalFloorSize + 10}"))
        
        // Reload
        RpgConfig.reload()
        
        assertEquals(originalFloorSize + 10, RpgConfig.Exploration.FLOOR_SIZE)
        
        // Cleanup and Reset
        RpgConfig.configPath = "config.yml"
        configFile.delete()
    }
}
