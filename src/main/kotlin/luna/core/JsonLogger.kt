package luna.core

import org.slf4j.LoggerFactory
import net.logstash.logback.argument.StructuredArguments.kv

/**
 * Utility for centralized JSON logging across all layers.
 */
object JsonLogger {
    // We use a dedicated logger name to facilitate filtering if needed
    private val logger = LoggerFactory.getLogger("JSON_LOGGER")

    /**
     * Logs a structured entry with the specified layer, component, and operation.
     *
     * @param layer The system layer (COMMAND, SERVICE, DATABASE)
     * @param component The name of the specific class or command
     * @param operation The method name or action performed
     * @param data A structured map of inputs/parameters or outputs/results
     * @param status Success or Failure status (default "SUCCESS")
     */
    fun log(
        layer: String,
        component: String,
        operation: String,
        data: Any?,
        status: String = "SUCCESS"
    ) {
        // Log at INFO level. The {} placeholder is filled by StructuredArguments.
        // Multiple arguments are appended as JSON fields by LogstashEncoder.
        logger.info(
            "Layer: {}, Component: {}, Operation: {}",
            kv("layer", layer),
            kv("component", component),
            kv("operation", operation),
            kv("data", data),
            kv("status", status)
        )
    }

    /**
     * Convenience method for logging errors.
     */
    fun error(
        layer: String,
        component: String,
        operation: String,
        data: Any?,
        errorMessage: String? = null
    ) {
        logger.error(
            "Layer: {}, Component: {}, Operation: {}, Error: {}",
            kv("layer", layer),
            kv("component", component),
            kv("operation", operation),
            kv("data", data),
            kv("status", "FAILURE"),
            kv("error_message", errorMessage)
        )
    }
}
