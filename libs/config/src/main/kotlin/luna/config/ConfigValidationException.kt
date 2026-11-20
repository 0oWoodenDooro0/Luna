package luna.config

/**
 * Dedicated exception thrown when Luna configuration fails strict validation.
 * Extends [IllegalStateException] for backwards compatibility and standard runtime contract.
 */
class ConfigValidationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
