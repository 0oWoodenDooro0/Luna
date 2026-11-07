package luna.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Automated guardrail enforcing mechanical rules documented in CODING_STANDARDS.md.
 */
class CodingStandardsTest {
    private val projectRoot = File(".").canonicalFile

    @Test
    fun `daemon singleton objects must enforce bounded capacity on in-memory collections`() {
        val mainRoots = findKotlinSourceRoots("main")
        assertTrue(mainRoots.isNotEmpty(), "At least one src/main/kotlin directory must exist")

        val violations = mutableListOf<String>()

        mainRoots.forEach { root ->
            root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
                val text = file.readText()
                // Detect singleton object declarations
                val objectRegex = Regex("""(?m)^(?:\\s*internal|\\s*public)?\\s*object\\s+(\\w+)""")
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
        val testRoots = findKotlinSourceRoots("test")
        assertTrue(testRoots.isNotEmpty(), "At least one src/test/kotlin directory must exist")

        val violations = mutableListOf<String>()
        val illegalChars = listOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
        val testNameRegex = Regex("""fun\s+`([^`]+)`\s*\(""")

        testRoots.forEach { root ->
            root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
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
        }

        assertTrue(
            violations.isEmpty(),
            "Found mechanical CODING_STANDARDS.md §5 violations (JVM-illegal characters in test names):\n" +
                violations.joinToString("\n"),
        )
    }

    @Test
    fun `dockerfile build configuration must account for all gradle subprojects in settings_gradle_kts`() {
        val settingsFile = File(projectRoot, "settings.gradle.kts")
        assertTrue(settingsFile.exists(), "settings.gradle.kts must exist")

        val dockerfile = File(projectRoot, "Dockerfile")
        assertTrue(dockerfile.exists(), "Dockerfile must exist")

        val dockerfileContent = dockerfile.readText()
        val includeRegex = Regex("""include\s*\(\s*["']([^"']+)["']\s*\)""")
        val includedProjects =
            includeRegex
                .findAll(settingsFile.readText())
                .map { it.groupValues[1].trimStart(':').replace(':', '/') }
                .toList()

        val violations = mutableListOf<String>()

        for (projectPath in includedProjects) {
            val buildKtsPattern = Regex("""COPY\s+.*$projectPath/build\.gradle\.kts""")
            val sourceCopyPattern = Regex("""COPY\s+.*$projectPath""")

            if (!buildKtsPattern.containsMatchIn(dockerfileContent) || !sourceCopyPattern.containsMatchIn(dockerfileContent)) {
                violations.add(
                    "Subproject '$projectPath' included in settings.gradle.kts is missing from Dockerfile build stage. " +
                        "Add COPY for '$projectPath/build.gradle.kts' and source directory to Dockerfile.",
                )
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Found Dockerfile build drift violations:\n" + violations.joinToString("\n"),
        )
    }

    @Test
    fun `source root discovery must reliably find all module kotlin source roots and ignore dot-directories and worktrees`() {
        val mainRoots = findKotlinSourceRoots("main")
        val mainPaths = mainRoots.map { it.relativeTo(projectRoot).path.replace('\\', '/') }.toSet()
        val expectedMain = setOf("src/main/kotlin", "libs/socialpeek/src/main/kotlin", "libs/config/src/main/kotlin", "mcp-server/src/main/kotlin")
        assertTrue(
            mainPaths == expectedMain,
            "Expected main roots $expectedMain but found $mainPaths",
        )

        val testRoots = findKotlinSourceRoots("test")
        val testPaths = testRoots.map { it.relativeTo(projectRoot).path.replace('\\', '/') }.toSet()
        val expectedTest = setOf("src/test/kotlin", "libs/socialpeek/src/test/kotlin", "libs/config/src/test/kotlin", "mcp-server/src/test/kotlin")
        assertTrue(
            testPaths == expectedTest,
            "Expected test roots $expectedTest but found $testPaths",
        )
    }

    @Test
    fun `bidirectional parity must be enforced between dot-env-example and codebase configuration declarations`() {
        val envContent =
            ConfigLoader.loadEnvExample()
                ?: File(projectRoot, ".env.example").takeIf { it.exists() }?.readText()
        assertNotNull(envContent, ".env.example must be discoverable and loadable via ConfigLoader or filesystem")

        val envKeyRegex = Regex("""^\s*(?:#\s*)?([A-Z][A-Z0-9_]+)\s*=""", RegexOption.MULTILINE)
        val declaredEnvKeys =
            envKeyRegex
                .findAll(envContent)
                .map { it.groupValues[1] }
                .toSet()

        assertTrue(
            declaredEnvKeys.isNotEmpty(),
            "Declared configuration keys extracted from .env.example must not be empty",
        )

        val mainRoots = findKotlinSourceRoots("main")
        assertTrue(mainRoots.isNotEmpty(), "At least one src/main/kotlin directory must exist")

        val codebaseKeys = mutableSetOf<String>()
        val configBracketRegex = Regex("""\bconfig\[\s*["']([A-Z][A-Z0-9_]+)["']\s*\]""")
        val pluginContextRegex = Regex("""context\.config(?:<[^>]+>)?\s*\(\s*["']([A-Z][A-Z0-9_]+)["']""")
        val sysEnvRegex = Regex("""System\.getenv\s*\(\s*["']([A-Z][A-Z0-9_]+)["']\s*\)""")
        val envBracketRegex = Regex("""\benv\[\s*["']([A-Z][A-Z0-9_]+)["']\s*\]""")
        val pluginNameRegex = Regex("""override\s+val\s+name\s*:\s*String\s*=\s*["']([^"']+)["']""")

        mainRoots.forEach { root ->
            root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
                val content = file.readText()
                configBracketRegex.findAll(content).forEach { codebaseKeys.add(it.groupValues[1]) }
                pluginContextRegex.findAll(content).forEach { codebaseKeys.add(it.groupValues[1]) }
                sysEnvRegex.findAll(content).forEach { codebaseKeys.add(it.groupValues[1]) }
                envBracketRegex.findAll(content).forEach { codebaseKeys.add(it.groupValues[1]) }

                if (content.contains(": LunaPlugin") || content.contains(": luna.plugin.LunaPlugin")) {
                    pluginNameRegex.find(content)?.let { match ->
                        val rawName = match.groupValues[1]
                        val flagName = "PLUGINS_${rawName.replace('-', '_').uppercase()}_ENABLED"
                        codebaseKeys.add(flagName)
                    }
                }
            }
        }

        // Dynamically inspect META-INF plugin service registration if available
        val metaInfService = File(projectRoot, "src/main/resources/META-INF/services/luna.plugin.LunaPlugin")
        if (metaInfService.exists()) {
            metaInfService
                .readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { className ->
                    runCatching {
                        val clazz = Class.forName(className)
                        val instance = clazz.getDeclaredConstructor().newInstance() as? luna.plugin.LunaPlugin
                        if (instance != null) {
                            codebaseKeys.add("PLUGINS_${instance.name.replace('-', '_').uppercase()}_ENABLED")
                        }
                    }
                }
        }

        // Backward compatibility fallback aliases accepted at runtime but not canonical
        val legacyFallbackAliases =
            setOf(
                "DISCORD_APP_ID",
                "REDIRECT_URI",
                "MCP_CLIENT_ID",
                "MCP_CLIENT_SECRET",
            )

        // Ensure legacy aliases are not erroneously declared as active in .env.example
        val declaredAliases = declaredEnvKeys.intersect(legacyFallbackAliases)
        assertTrue(
            declaredAliases.isEmpty(),
            "Legacy fallback aliases must not be declared as active keys in .env.example: $declaredAliases",
        )

        val canonicalCodebaseKeys = codebaseKeys - legacyFallbackAliases
        val missingInEnv = canonicalCodebaseKeys - declaredEnvKeys
        val unconsumedInCode = declaredEnvKeys - canonicalCodebaseKeys

        assertTrue(
            missingInEnv.isEmpty() && unconsumedInCode.isEmpty(),
            buildString {
                appendLine("Bidirectional parity violation between .env.example and codebase configuration declarations:")
                if (missingInEnv.isNotEmpty()) {
                    appendLine("  [Missing in .env.example] The following keys are used in codebase but missing in .env.example:")
                    missingInEnv.sorted().forEach { appendLine("    - $it") }
                }
                if (unconsumedInCode.isNotEmpty()) {
                    appendLine("  [Unconsumed in codebase] The following keys are declared in .env.example but have no active codebase reference:")
                    unconsumedInCode.sorted().forEach { appendLine("    - $it") }
                }
            },
        )
    }

    private fun findKotlinSourceRoots(parentDirName: String): List<File> =
        projectRoot
            .walkTopDown()
            .onEnter { dir ->
                val name = dir.name
                !name.startsWith(".") && name != "build"
            }.filter { dir ->
                dir.isDirectory &&
                    dir.name == "kotlin" &&
                    dir.parentFile?.name == parentDirName
            }.toList()

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
