package luna.core

import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals

class YamlLoadTest {
    @Test
    fun testYamlParsingDirect() {
        val yamlStr =
            """
            curtly:
              baseUrl: "https://mycustom.domain"
            """.trimIndent()
        val yaml = Yaml()
        val config = yaml.load<Map<String, Any>>(ByteArrayInputStream(yamlStr.toByteArray()))
        val curtlyConfig = config["curtly"] as? Map<*, *>
        val rawBaseUrl = curtlyConfig?.get("baseUrl") as? String
        assertEquals("https://mycustom.domain", rawBaseUrl)
    }

    @Test
    fun testYamlParsingPlaceholder() {
        val yamlStr =
            """
            curtly:
              baseUrl: ${'$'}{?CURTLY_BASE_URL}
            """.trimIndent()
        val yaml = Yaml()
        val config = yaml.load<Map<String, Any>>(ByteArrayInputStream(yamlStr.toByteArray()))
        val curtlyConfig = config["curtly"] as? Map<*, *>
        val rawBaseUrl = curtlyConfig?.get("baseUrl") as? String
        assertEquals("\${?CURTLY_BASE_URL}", rawBaseUrl)

        val trimmed = rawBaseUrl!!.trim()
        val inner = trimmed.substring(2, trimmed.length - 1).trim()
        val cleanInner = if (inner.startsWith("?")) inner.substring(1) else inner
        assertEquals("CURTLY_BASE_URL", cleanInner)
    }
}
