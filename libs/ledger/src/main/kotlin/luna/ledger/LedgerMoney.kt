package luna.ledger

import java.math.BigDecimal
import java.util.Currency

object LedgerMoney {
    private val fractionDigitsOverrides = mapOf("TWD" to 0)
    private val availableCurrencyCodes = Currency.getAvailableCurrencies().map { it.currencyCode }.toSet()

    fun normalizeCurrency(rawCurrency: String): String {
        val code = rawCurrency.trim().uppercase()
        if (code.length != 3 || !code.all { it in 'A'..'Z' } || code !in availableCurrencyCodes) {
            throw LedgerValidationException("Invalid or unknown ISO-4217 currency code: '$rawCurrency'")
        }
        val digits = resolveFractionDigitsUnchecked(code)
        if (digits < 0) {
            throw LedgerValidationException("Currency '$code' does not support decimal minor units")
        }
        return code
    }

    fun fractionDigits(currency: String): Int {
        val normalized = normalizeCurrency(currency)
        return resolveFractionDigitsUnchecked(normalized)
    }

    fun parseToMinorUnits(
        amount: String,
        currency: String,
    ): Long {
        val normalizedCurrency = normalizeCurrency(currency)
        val digits = resolveFractionDigitsUnchecked(normalizedCurrency)
        val trimmed = amount.trim()
        if (trimmed.isEmpty()) {
            throw LedgerValidationException("Amount must not be blank")
        }

        val decimal =
            try {
                BigDecimal(trimmed)
            } catch (e: NumberFormatException) {
                throw LedgerValidationException("Invalid numeric amount: '$amount'")
            }

        if (decimal <= BigDecimal.ZERO) {
            throw LedgerValidationException("Amount must be positive, got '$amount'")
        }

        val effectiveScale = decimal.stripTrailingZeros().scale().coerceAtLeast(0)
        if (effectiveScale > digits) {
            throw LedgerValidationException(
                "Amount '$amount' has $effectiveScale decimal places, exceeding max $digits for $normalizedCurrency",
            )
        }

        return try {
            decimal.movePointRight(digits).longValueExact()
        } catch (e: ArithmeticException) {
            throw LedgerValidationException("Amount '$amount' overflows supported minor-unit range")
        }
    }

    fun formatFromMinorUnits(
        amountMinor: Long,
        currency: String,
    ): String {
        val digits = fractionDigits(currency)
        return BigDecimal.valueOf(amountMinor, digits).toPlainString()
    }

    private fun resolveFractionDigitsUnchecked(normalizedCode: String): Int =
        fractionDigitsOverrides[normalizedCode] ?: Currency.getInstance(normalizedCode).defaultFractionDigits
}
