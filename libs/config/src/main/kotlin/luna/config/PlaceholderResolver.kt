package luna.config

/**
 * Resolves `${VAR:default}` or `${?VAR}` syntax inside raw configuration strings.
 */
object PlaceholderResolver {
    private val PLACEHOLDER_REGEX = Regex("""\$\{\??([^}:]+)(?::([^}]*))?\}""")

    fun resolve(
        raw: String,
        config: Map<String, Any?>,
        systemEnv: Map<String, String> = System.getenv(),
    ): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("\${") && trimmed.endsWith("}")) {
            val inner = trimmed.substring(2, trimmed.length - 1).trim()
            val cleanInner = if (inner.startsWith("?")) inner.substring(1) else inner
            val parts = cleanInner.split(":", limit = 2)
            val envVarName = parts[0].trim()
            val defaultValue = if (parts.size > 1) parts[1].trim() else ""
            val resolvedVal =
                (config[envVarName] as? String)
                    ?: (config[envVarName]?.toString())
                    ?: systemEnv[envVarName]
            return if (!resolvedVal.isNullOrEmpty()) resolvedVal else defaultValue
        }

        if (raw.contains("\${")) {
            return PLACEHOLDER_REGEX.replace(raw) { matchResult ->
                val envVarName = matchResult.groupValues[1].trim()
                val defaultValue = if (matchResult.groupValues.size > 2) matchResult.groupValues[2].trim() else ""
                val resolvedVal =
                    (config[envVarName] as? String)
                        ?: (config[envVarName]?.toString())
                        ?: systemEnv[envVarName]
                if (!resolvedVal.isNullOrEmpty()) resolvedVal else defaultValue
            }
        }

        return raw
    }
}
