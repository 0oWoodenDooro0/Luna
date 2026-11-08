package luna.chatter

import luna.plugin.PluginContext

/**
 * Configuration for Cloud Laya Studio System 1 decision client.
 *
 * @property apiKey Laya Studio API Bearer token.
 * @property threshold Decision confidence threshold (default 0.7).
 * @property endpoint Endpoint URL for System 1 decisions (default "https://api.laya.studio/v1/systemone").
 */
data class LayaConfig(
    val apiKey: String = "",
    val threshold: Double = DEFAULT_THRESHOLD,
    val endpoint: String = DEFAULT_ENDPOINT,
) {
    val isConfigured: Boolean
        get() = apiKey.isNotBlank()

    val cleanEndpoint: String
        get() = endpoint.trim().trimEnd('/')

    companion object {
        const val DEFAULT_THRESHOLD = 0.7
        const val DEFAULT_ENDPOINT = "https://api.laya.studio/v1/systemone"

        fun fromEnv(env: Map<String, String> = emptyMap()): LayaConfig {
            val key = env["LAYA_API_KEY"]?.trim().orEmpty()
            val threshold = env["LAYA_THRESHOLD"]?.trim()?.toDoubleOrNull() ?: DEFAULT_THRESHOLD
            val rawEndpoint = env["LAYA_ENDPOINT"]?.trim()
            val endpoint = if (!rawEndpoint.isNullOrBlank()) rawEndpoint.trimEnd('/') else DEFAULT_ENDPOINT
            return LayaConfig(
                apiKey = key,
                threshold = threshold,
                endpoint = endpoint,
            )
        }

        fun fromPluginContext(context: PluginContext): LayaConfig {
            val key = context.config("LAYA_API_KEY", "")
            val threshold = context.config("LAYA_THRESHOLD", DEFAULT_THRESHOLD)
            val rawEndpoint = context.config("LAYA_ENDPOINT", DEFAULT_ENDPOINT)
            return LayaConfig(
                apiKey = key.trim(),
                threshold = threshold,
                endpoint = rawEndpoint.trim().trimEnd('/'),
            )
        }
    }
}
