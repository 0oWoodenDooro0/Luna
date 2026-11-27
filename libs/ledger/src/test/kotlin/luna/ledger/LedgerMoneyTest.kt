package luna.ledger

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LedgerMoneyTest {
    @Test
    fun `parses and formats zero-decimal currencies TWD and JPY`() {
        assertEquals(150L, LedgerMoney.parseToMinorUnits("150", "TWD"))
        assertEquals(150L, LedgerMoney.parseToMinorUnits("150.00", "twd"))
        assertEquals("150", LedgerMoney.formatFromMinorUnits(150L, "TWD"))

        assertEquals(1200L, LedgerMoney.parseToMinorUnits("1200", "JPY"))
        assertEquals("1200", LedgerMoney.formatFromMinorUnits(1200L, "JPY"))
    }

    @Test
    fun `parses and formats two-decimal currencies USD and EUR`() {
        assertEquals(1250L, LedgerMoney.parseToMinorUnits("12.50", "USD"))
        assertEquals(1250L, LedgerMoney.parseToMinorUnits("12.5", "usd"))
        assertEquals(1200L, LedgerMoney.parseToMinorUnits("12", "EUR"))
        assertEquals("12.50", LedgerMoney.formatFromMinorUnits(1250L, "USD"))
        assertEquals("12.00", LedgerMoney.formatFromMinorUnits(1200L, "EUR"))
        assertEquals("-5.00", LedgerMoney.formatFromMinorUnits(-500L, "USD"))
    }

    @Test
    fun `rejects non-positive amounts`() {
        assertThrows(LedgerValidationException::class.java) {
            LedgerMoney.parseToMinorUnits("0", "TWD")
        }
        assertThrows(LedgerValidationException::class.java) {
            LedgerMoney.parseToMinorUnits("0.00", "USD")
        }
        assertThrows(LedgerValidationException::class.java) {
            LedgerMoney.parseToMinorUnits("-10", "TWD")
        }
        assertThrows(LedgerValidationException::class.java) {
            LedgerMoney.parseToMinorUnits("", "TWD")
        }
        assertThrows(LedgerValidationException::class.java) {
            LedgerMoney.parseToMinorUnits("abc", "TWD")
        }
    }

    @Test
    fun `rejects unknown or invalid ISO-4217 currency codes`() {
        assertThrows(LedgerValidationException::class.java) {
            LedgerMoney.parseToMinorUnits("100", "INVALID")
        }
        assertThrows(LedgerValidationException::class.java) {
            LedgerMoney.parseToMinorUnits("100", "")
        }
        assertThrows(LedgerValidationException::class.java) {
            LedgerMoney.parseToMinorUnits("100", "ZZZ")
        }
        assertThrows(LedgerValidationException::class.java) {
            LedgerMoney.parseToMinorUnits("100", "XXX")
        }
    }

    @Test
    fun `rejects excessive decimal precision for currency`() {
        assertThrows(LedgerValidationException::class.java) {
            LedgerMoney.parseToMinorUnits("100.5", "TWD")
        }
        assertThrows(LedgerValidationException::class.java) {
            LedgerMoney.parseToMinorUnits("99.1", "JPY")
        }
        assertThrows(LedgerValidationException::class.java) {
            LedgerMoney.parseToMinorUnits("12.345", "USD")
        }
    }
}
