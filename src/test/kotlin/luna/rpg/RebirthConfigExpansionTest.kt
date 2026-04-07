package luna.rpg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class RebirthConfigExpansionTest {

    @Test
    fun `test rebirth config includes new expansion settings`() {
        val config = RebirthConfig(
            maxResourceLevel = 15,
            resourceBonusPerLevel = 0.1,
            maxEfficientLevel = 12,
            efficientBonusPerLevel = 0.08
        )
        
        assertEquals(15, config.maxResourceLevel)
        assertEquals(0.1, config.resourceBonusPerLevel)
        assertEquals(12, config.maxEfficientLevel)
        assertEquals(0.08, config.efficientBonusPerLevel)
    }

    @Test
    fun `test RpgConfigLoader parses new expansion settings`() {
        val configContent = """
            rebirth:
              max_resource_level: 15
              resource_bonus_per_level: 0.1
              max_efficient_level: 12
              efficient_bonus_per_level: 0.08
        """.trimIndent()
        
        val tempFile = File.createTempFile("config_test", ".yml")
        tempFile.writeText(configContent)
        
        try {
            val loader = RpgConfigLoader(tempFile.absolutePath)
            val data = loader.load()
            
            assertEquals(15, data.rebirth.maxResourceLevel)
            assertEquals(0.1, data.rebirth.resourceBonusPerLevel)
            assertEquals(12, data.rebirth.maxEfficientLevel)
            assertEquals(0.08, data.rebirth.efficientBonusPerLevel)
        } finally {
            tempFile.delete()
        }
    }
}
