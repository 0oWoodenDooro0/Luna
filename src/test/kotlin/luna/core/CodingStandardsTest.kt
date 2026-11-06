package luna.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Automated guardrail enforcing mechanical rules documented in CODING_STANDARDS.md.
 */
class CodingStandardsTest {
    private val projectRoot = File(".").canonicalFile

    @Test
    fun `daemon singleton objects must enforce bounded capacity on in-memory collections`() {
        val srcMain = File(projectRoot, "src/main/kotlin/luna")
        assertTrue(srcMain.exists(), "src/main/kotlin/luna directory must exist")

        val violations = mutableListOf<String>()

        srcMain.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            // Detect singleton object declarations
            val objectRegex = Regex("""(?m)^(?:\s*internal|\s*public)?\s*object\s+(\w+)""")
            val objectMatches = objectRegex.findAll(text).toList()

            for (match in objectMatches) {
                val objectName = match.groupValues[1]
                val objectStart = match.range.first
                // Simple block boundary extraction
                val remaining = text.substring(objectStart)
                val blockEnd = findClosingBraceIndex(remaining)
                val objectBody = if (blockEnd > 0) remaining.substring(0, blockEnd) else remaining

                // Check for unbounded collection patterns
                val unboundedPatterns =
                    listOf(
                        Regex("""ConcurrentHashMap\.newKeySet"""),
                        Regex("""val\s+\w+\s*=\s*ConcurrentHashMap<"""),
                        Regex("""val\s+\w+\s*=\s*mutableSetOf<"""),
                        Regex("""val\s+\w+\s*=\s*mutableMapOf<"""),
                        Regex("""val\s+\w+\s*=\s*HashSet<"""),
                    )

                for (pattern in unboundedPatterns) {
                    val found = pattern.findAll(objectBody).toList()
                    for (f in found) {
                        val lineIndex = objectBody.substring(0, f.range.first).lines().size
                        // Check if file or body provides bounding guardrail (e.g. MAX_, capacity, removeEldestEntry)
                        val isBounded =
                            objectBody.contains("removeEldestEntry") ||
                                objectBody.contains("MAX_") ||
                                objectBody.contains("BoundedStatusCache") ||
                                objectBody.contains("capacity")
                        if (!isBounded) {
                            violations.add(
                                "${file.relativeTo(projectRoot)}:$lineIndex in object $objectName: " +
                                    "Unbounded collection '${f.value}' found. " +
                                    "Enforce capacity bounds or LRU eviction per CODING_STANDARDS.md §1.",
                            )
                        }
                    }
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Found mechanical CODING_STANDARDS.md §1 violations (unbounded singleton collections):\n" +
                violations.joinToString("\n"),
        )
    }

    @Test
    fun `integration tests must not use brittle assertEquals on formatted input content`() {
        val summarizerTests = File(projectRoot, "src/test/kotlin/luna/summarizer")
        assertTrue(summarizerTests.exists(), "src/test/kotlin/luna/summarizer directory must exist")

        val violations = mutableListOf<String>()
        val brittleAssertRegex =
            Regex("""assertEquals\s*\([^,]+,\s*(?:capturedInput|capturedInitialInput|input)\.content\s*\)""")

        summarizerTests.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            file.useLines { lines ->
                lines.forEachIndexed { index, line ->
                    if (brittleAssertRegex.containsMatchIn(line)) {
                        violations.add(
                            "${file.relativeTo(projectRoot)}:${index + 1}: " +
                                "Brittle assertEquals on formatted transcript content found: '$line'. " +
                                "Use substring containment (assertTrue(content.contains(...))) per CODING_STANDARDS.md §5.",
                        )
                    }
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Found mechanical CODING_STANDARDS.md §5 violations (brittle input.content assertions):\n" +
                violations.joinToString("\n"),
        )
    }

    @Test
    fun `test function identifiers must not contain JVM-illegal characters`() {
        val srcTest = File(projectRoot, "src/test/kotlin")
        assertTrue(srcTest.exists(), "src/test/kotlin directory must exist")

        val violations = mutableListOf<String>()
        val illegalChars = listOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
        val testNameRegex = Regex("""fun\s+`([^`]+)`\s*\(""")

        srcTest.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            file.useLines { lines ->
                lines.forEachIndexed { index, line ->
                    val match = testNameRegex.find(line)
                    if (match != null) {
                        val testName = match.groupValues[1]
                        val foundIllegal = illegalChars.filter { it in testName }
                        if (foundIllegal.isNotEmpty()) {
                            violations.add(
                                "${file.relativeTo(projectRoot)}:${index + 1}: " +
                                    "Test name contains JVM-illegal characters $foundIllegal: '$testName'. " +
                                    "Use natural phrasing per CODING_STANDARDS.md §5.",
                            )
                        }
                    }
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Found mechanical CODING_STANDARDS.md §5 violations (JVM-illegal characters in test names):\n" +
                violations.joinToString("\n"),
        )
    }

    private fun findClosingBraceIndex(str: String): Int {
        var depth = 0
        var inside = false
        for (i in str.indices) {
            when (str[i]) {
                '{' -> {
                    depth++
                    inside = true
                }

                '}' -> {
                    depth--
                    if (inside && depth == 0) return i
                }
            }
        }
        return -1
    }
}
