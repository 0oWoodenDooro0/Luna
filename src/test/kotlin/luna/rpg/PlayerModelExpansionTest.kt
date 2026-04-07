package luna.rpg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlayerModelExpansionTest {

    @Test
    fun `test player includes new rebirth levels`() {
        val player = Player(
            id = "123",
            name = "Test",
            attributes = RpgAttributes(100, 100, 10, 10, 10),
            rebirthResourceLevel = 5,
            rebirthEfficientLevel = 3
        )
        
        assertEquals(5, player.rebirthResourceLevel)
        assertEquals(3, player.rebirthEfficientLevel)
    }

    @Test
    fun `test rebirthReset preserves new rebirth levels`() {
        val player = Player(
            id = "123",
            name = "Test",
            attributes = RpgAttributes(100, 100, 10, 10, 10),
            rebirthResourceLevel = 5,
            rebirthEfficientLevel = 3
        )
        
        val rebornPlayer = player.rebirthReset(10)
        
        assertEquals(5, rebornPlayer.rebirthResourceLevel)
        assertEquals(3, rebornPlayer.rebirthEfficientLevel)
        assertEquals(1, rebornPlayer.rebirthCount)
        assertEquals(10, rebornPlayer.rebirthPoints)
    }
}
