package luna.config

import java.io.File

/**
 * Deep module responsible for parsing .env file syntax into key-value pairs.
 * Uniformly strips quotes, skips comments, and supports the 'export ' prefix.
 */
object DotenvParser {
    fun parse(lines: Sequence<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue
            }
            val line =
                if (trimmed.startsWith("export ")) {
                    trimmed.removePrefix("export ").trimStart()
                } else {
                    trimmed
                }
            val parts = line.split("=", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                var value = parts[1].trim()
                if ((value.startsWith("\"") && value.endsWith("\"")) ||
                    (value.startsWith("'") && value.endsWith("'"))
                ) {
                    value = value.substring(1, value.length - 1)
                }
                if (key.isNotEmpty()) {
                    result[key] = value
                }
            }
        }
        return result
    }

    fun parse(content: String): Map<String, String> = parse(content.lineSequence())

    fun parse(file: File): Map<String, String> = file.useLines { parse(it) }
}
