import java.io.File

fun findKotlinSourceRoots(
    projectRoot: File,
    parentDirName: String,
): List<File> =
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

fun findClosingBraceIndex(str: String): Int {
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

fun skipWsAndComments(
    text: String,
    startIdx: Int,
    startLine: Int,
): Pair<Int, Int> {
    var idx = startIdx
    var line = startLine
    val n = text.length
    while (idx < n) {
        val c = text[idx]
        when {
            c == '\n' -> {
                line++
                idx++
            }
            c == ' ' || c == '\t' || c == '\r' -> idx++
            text.startsWith("//", idx) -> {
                while (idx < n && text[idx] != '\n') idx++
            }
            text.startsWith("/*", idx) -> {
                var depth = 1
                idx += 2
                while (idx < n && depth > 0) {
                    when {
                        text.startsWith("/*", idx) -> {
                            depth++
                            idx += 2
                        }
                        text.startsWith("*/", idx) -> {
                            depth--
                            idx += 2
                        }
                        else -> {
                            if (text[idx] == '\n') line++
                            idx++
                        }
                    }
                }
            }
            else -> break
        }
    }
    return idx to line
}

fun skipStringOrChar(
    text: String,
    startIdx: Int,
    startLine: Int,
): Pair<Int, Int> {
    var idx = startIdx
    var line = startLine
    val n = text.length
    if (text.startsWith("\"\"\"", idx)) {
        idx += 3
        while (idx < n) {
            if (text.startsWith("\"\"\"", idx)) {
                idx += 3
                break
            }
            if (text[idx] == '\n') line++
            idx++
        }
        return idx to line
    }
    val quote = text[idx]
    idx++
    while (idx < n) {
        val c = text[idx]
        if (c == '\\') {
            idx += 2
            continue
        }
        if (c == '\n') line++
        if (c == quote) {
            idx++
            break
        }
        idx++
    }
    return idx to line
}

fun findBareBooleanAssertions(text: String): List<Int> {
    val n = text.length
    var i = 0
    var line = 1
    val violationLines = mutableListOf<Int>()

    while (i < n) {
        val c = text[i]
        when {
            c == '\n' -> {
                line++
                i++
            }
            text.startsWith("//", i) -> {
                while (i < n && text[i] != '\n') i++
            }
            text.startsWith("/*", i) -> {
                val (nextIdx, nextLine) = skipWsAndComments(text, i, line)
                i = nextIdx
                line = nextLine
            }
            c == '"' || c == '\'' -> {
                val (nextIdx, nextLine) = skipStringOrChar(text, i, line)
                i = nextIdx
                line = nextLine
            }
            text.startsWith("assertTrue", i) || text.startsWith("assertFalse", i) -> {
                val kw = if (text.startsWith("assertTrue", i)) "assertTrue" else "assertFalse"
                val prevOk = i == 0 || (!text[i - 1].isLetterOrDigit() && text[i - 1] != '_')
                val afterKw = i + kw.length
                val nextOk = afterKw < n && (!text[afterKw].isLetterOrDigit() && text[afterKw] != '_')
                if (prevOk && nextOk) {
                    val callLine = line
                    val (openParenIdx, openLine) = skipWsAndComments(text, afterKw, line)
                    if (openParenIdx < n && text[openParenIdx] == '(') {
                        var j = openParenIdx + 1
                        var jLine = openLine
                        var parenDepth = 1
                        var bracketDepth = 0
                        var braceDepth = 0
                        var argCount = 0
                        var hasToken = false

                        while (j < n && parenDepth > 0) {
                            val ch = text[j]
                            when {
                                ch == '\n' -> {
                                    jLine++
                                    j++
                                }
                                ch == ' ' || ch == '\t' || ch == '\r' -> j++
                                text.startsWith("//", j) || text.startsWith("/*", j) -> {
                                    val (nj, nl) = skipWsAndComments(text, j, jLine)
                                    j = nj
                                    jLine = nl
                                }
                                ch == '"' || ch == '\'' -> {
                                    hasToken = true
                                    val (nj, nl) = skipStringOrChar(text, j, jLine)
                                    j = nj
                                    jLine = nl
                                }
                                ch == '(' -> {
                                    hasToken = true
                                    parenDepth++
                                    j++
                                }
                                ch == ')' -> {
                                    parenDepth--
                                    if (parenDepth == 0) {
                                        if (hasToken) argCount++
                                        j++
                                        break
                                    }
                                    hasToken = true
                                    j++
                                }
                                ch == '[' -> {
                                    hasToken = true
                                    bracketDepth++
                                    j++
                                }
                                ch == ']' -> {
                                    hasToken = true
                                    bracketDepth = maxOf(0, bracketDepth - 1)
                                    j++
                                }
                                ch == '{' -> {
                                    hasToken = true
                                    braceDepth++
                                    j++
                                }
                                ch == '}' -> {
                                    hasToken = true
                                    braceDepth = maxOf(0, braceDepth - 1)
                                    j++
                                }
                                ch == ',' && parenDepth == 1 && bracketDepth == 0 && braceDepth == 0 -> {
                                    if (hasToken) argCount++
                                    hasToken = false
                                    j++
                                }
                                else -> {
                                    hasToken = true
                                    j++
                                }
                            }
                        }

                        val (afterCloseIdx, _) = skipWsAndComments(text, j, jLine)
                        val hasTrailingLambda = afterCloseIdx < n && text[afterCloseIdx] == '{'
                        if (argCount < 2 && !hasTrailingLambda) {
                            violationLines.add(callLine)
                        }
                        i = afterKw
                        continue
                    }
                }
                i++
            }
            else -> i++
        }
    }
    return violationLines
}

fun checkBoundedSingletonCollections(projectRoot: File): List<String> {
    val mainRoots = findKotlinSourceRoots(projectRoot, "main")
    if (mainRoots.isEmpty()) return listOf("At least one src/main/kotlin directory must exist")

    val violations = mutableListOf<String>()
    val objectRegex = Regex("""(?m)^(?:\s*internal|\s*public)?\s*object\s+(\w+)""")
    val unboundedPatterns =
        listOf(
            Regex("""(?m)^[ \t]{2,4}(?:private\s+|internal\s+|public\s+)?val\s+\w+\s*=\s*ConcurrentHashMap\.newKeySet"""),
            Regex("""(?m)^[ \t]{2,4}(?:private\s+|internal\s+|public\s+)?val\s+\w+\s*=\s*ConcurrentHashMap<"""),
            Regex("""(?m)^[ \t]{2,4}(?:private\s+|internal\s+|public\s+)?val\s+\w+\s*=\s*mutableSetOf<"""),
            Regex("""(?m)^[ \t]{2,4}(?:private\s+|internal\s+|public\s+)?val\s+\w+\s*=\s*mutableMapOf<"""),
            Regex("""(?m)^[ \t]{2,4}(?:private\s+|internal\s+|public\s+)?val\s+\w+\s*=\s*HashSet<"""),
        )

    mainRoots.forEach { root ->
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            for (match in objectRegex.findAll(text)) {
                val objectName = match.groupValues[1]
                val remaining = text.substring(match.range.first)
                val blockEnd = findClosingBraceIndex(remaining)
                val objectBody = if (blockEnd > 0) remaining.substring(0, blockEnd) else remaining

                val isBounded =
                    objectBody.contains("removeEldestEntry") ||
                        objectBody.contains("MAX_") ||
                        objectBody.contains("BoundedStatusCache") ||
                        objectBody.contains("capacity")

                if (!isBounded) {
                    for (pattern in unboundedPatterns) {
                        for (f in pattern.findAll(objectBody)) {
                            val lineIndex = objectBody.substring(0, f.range.first).lines().size
                            val relPath = file.relativeTo(projectRoot).path.replace('\\', '/')
                            violations.add(
                                "[Rule 1: bounded-singleton-collections] $relPath:$lineIndex in object $objectName: " +
                                    "Unbounded collection '${f.value}' found. " +
                                    "Enforce capacity bounds or LRU eviction per CODING_STANDARDS.md §1.",
                            )
                        }
                    }
                }
            }
        }
    }
    return violations
}

fun checkBrittleServiceTestAssertions(projectRoot: File): List<String> {
    val serviceTests =
        findKotlinSourceRoots(projectRoot, "test")
            .flatMap { root ->
                root.walkTopDown().filter { it.isFile && it.name.endsWith("ServiceTest.kt") }.toList()
            }
    if (serviceTests.isEmpty()) return listOf("At least one *ServiceTest.kt file must exist")

    val violations = mutableListOf<String>()
    val brittleAssertRegex =
        Regex("""assertEquals\s*\([^,]+,\s*(?:capturedInput|capturedInitialInput|input)\.content\s*\)""")

    serviceTests.forEach { file ->
        val relPath = file.relativeTo(projectRoot).path.replace('\\', '/')
        file.useLines { lines ->
            lines.forEachIndexed { index, line ->
                if (brittleAssertRegex.containsMatchIn(line)) {
                    violations.add(
                        "[Rule 2: brittle-service-test-assert] $relPath:${index + 1}: " +
                            "Brittle assertEquals on formatted transcript content found: '$line'. " +
                            "Use substring containment (assertTrue(content.contains(...))) per CODING_STANDARDS.md §5.",
                    )
                }
            }
        }
    }
    return violations
}

fun checkJvmLegalTestNames(projectRoot: File): List<String> {
    val testRoots = findKotlinSourceRoots(projectRoot, "test")
    if (testRoots.isEmpty()) return listOf("At least one src/test/kotlin directory must exist")

    val violations = mutableListOf<String>()
    val illegalChars = listOf('<', '>', ':', '"', '/', '\\', '|', '?', '*', '.', ';', '[', ']')
    val testNameRegex = Regex("""fun\s+`([^`]+)`\s*\(""")

    testRoots.forEach { root ->
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val relPath = file.relativeTo(projectRoot).path.replace('\\', '/')
            file.useLines { lines ->
                lines.forEachIndexed { index, line ->
                    val match = testNameRegex.find(line)
                    if (match != null) {
                        val testName = match.groupValues[1]
                        val foundIllegal = illegalChars.filter { it in testName }
                        if (foundIllegal.isNotEmpty()) {
                            violations.add(
                                "[Rule 3: jvm-legal-test-names] $relPath:${index + 1}: " +
                                    "Test name contains JVM-illegal characters $foundIllegal: '$testName'. " +
                                    "Use natural phrasing per CODING_STANDARDS.md §5.",
                            )
                        }
                    }
                }
            }
        }
    }
    return violations
}

fun checkDockerfileSubprojectParity(projectRoot: File): List<String> {
    val settingsFile = File(projectRoot, "settings.gradle.kts")
    if (!settingsFile.exists()) return listOf("settings.gradle.kts must exist")

    val dockerfile = File(projectRoot, "Dockerfile")
    if (!dockerfile.exists()) return listOf("Dockerfile must exist")

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
                "[Rule 4: dockerfile-subproject-parity] Subproject '$projectPath' included in settings.gradle.kts " +
                    "is missing from Dockerfile build stage. Add COPY for '$projectPath/build.gradle.kts' and source directory to Dockerfile.",
            )
        }
    }
    return violations
}

fun checkEnvExampleParity(projectRoot: File): List<String> {
    val envFile = File(projectRoot, ".env.example")
    if (!envFile.exists()) return listOf(".env.example must exist at project root")

    val envContent = envFile.readText()
    val envKeyRegex = Regex("""^\s*(?:#\s*)?([A-Z][A-Z0-9_]+)\s*=""", RegexOption.MULTILINE)
    val declaredEnvKeys = envKeyRegex.findAll(envContent).map { it.groupValues[1] }.toSet()
    if (declaredEnvKeys.isEmpty()) {
        return listOf("[Rule 5: env-example-parity] Declared configuration keys extracted from .env.example must not be empty")
    }

    val mainRoots = findKotlinSourceRoots(projectRoot, "main")
    if (mainRoots.isEmpty()) return listOf("At least one src/main/kotlin directory must exist")

    val codebaseKeys = mutableSetOf<String>()
    val sysEnvRegex = Regex("""System\.getenv\s*\(\s*["']([A-Z][A-Z0-9_]+)["']\s*\)""")
    val envReaderSingleKeyRegex =
        Regex(
            """\b(?:env|envReader)\s*\.\s*(?:string|requiredString|int|long|double|boolean|csv|port)\s*\(\s*["']([A-Z][A-Z0-9_]+)["']""",
        )
    val envReaderFirstStringRegex = Regex("""\b(?:env|envReader)\s*\.\s*firstString\s*\(([^)]*)\)""")
    val keyLiteralRegex = Regex("""["']([A-Z][A-Z0-9_]+)["']""")
    val pluginNameRegex = Regex("""override\s+val\s+name\s*:\s*String\s*=\s*["']([^"']+)["']""")

    mainRoots.forEach { root ->
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val content = file.readText()
            sysEnvRegex.findAll(content).forEach { codebaseKeys.add(it.groupValues[1]) }
            envReaderSingleKeyRegex.findAll(content).forEach { codebaseKeys.add(it.groupValues[1]) }
            envReaderFirstStringRegex.findAll(content).forEach { callMatch ->
                val keyArgs = callMatch.groupValues[1].substringBefore("default")
                keyLiteralRegex.findAll(keyArgs).forEach { keyMatch ->
                    codebaseKeys.add(keyMatch.groupValues[1])
                }
            }

            if (content.contains(": LunaPlugin") || content.contains(": luna.plugin.LunaPlugin")) {
                pluginNameRegex.find(content)?.let { match ->
                    val rawName = match.groupValues[1]
                    codebaseKeys.add("PLUGINS_${rawName.replace('-', '_').uppercase()}_ENABLED")
                }
            }
        }
    }

    val legacyFallbackAliases =
        setOf(
            "DISCORD_APP_ID",
            "REDIRECT_URI",
            "MCP_CLIENT_ID",
            "MCP_CLIENT_SECRET",
        )

    val violations = mutableListOf<String>()
    val declaredAliases = declaredEnvKeys.intersect(legacyFallbackAliases)
    if (declaredAliases.isNotEmpty()) {
        violations.add(
            "[Rule 5: env-example-parity] Legacy fallback aliases must not be declared as active keys in .env.example: $declaredAliases",
        )
    }

    val canonicalCodebaseKeys = codebaseKeys - legacyFallbackAliases
    val missingInEnv = canonicalCodebaseKeys - declaredEnvKeys
    val unconsumedInCode = declaredEnvKeys - canonicalCodebaseKeys

    if (missingInEnv.isNotEmpty() || unconsumedInCode.isNotEmpty()) {
        violations.add(
            buildString {
                appendLine("[Rule 5: env-example-parity] Bidirectional parity violation between .env.example and codebase configuration declarations:")
                if (missingInEnv.isNotEmpty()) {
                    appendLine("  [Missing in .env.example] Used in codebase but missing in .env.example:")
                    missingInEnv.sorted().forEach { appendLine("    - $it") }
                }
                if (unconsumedInCode.isNotEmpty()) {
                    append("  [Unconsumed in codebase] Declared in .env.example but has no active codebase reference:")
                    unconsumedInCode.sorted().forEach { append("\n    - $it") }
                }
            },
        )
    }
    return violations
}

fun checkNoGitignoredKotlinSources(projectRoot: File): List<String> {
    if (!File(projectRoot, ".git").exists()) {
        return emptyList()
    }
    val allRoots = findKotlinSourceRoots(projectRoot, "main") + findKotlinSourceRoots(projectRoot, "test")
    val relPaths =
        allRoots.flatMap { root ->
            root
                .walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "kts") }
                .map { it.relativeTo(projectRoot).path.replace('\\', '/') }
        }
    if (relPaths.isEmpty()) return emptyList()

    val process =
        ProcessBuilder("git", "check-ignore", "--stdin", "-z")
            .directory(projectRoot)
            .apply {
                environment().remove("GIT_DIR")
                environment().remove("GIT_WORK_TREE")
                environment().remove("GIT_INDEX_FILE")
            }.start()
    process.outputStream.bufferedWriter().use { writer ->
        relPaths.forEach { path ->
            writer.write(path)
            writer.write('\u0000'.code)
        }
    }
    val stdout = process.inputStream.bufferedReader().use { it.readText() }
    val stderr = process.errorStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    if (exitCode != 0 && exitCode != 1) {
        return listOf("[Rule 6: gitignored-kotlin-sources] git check-ignore failed with exit code $exitCode: $stderr")
    }
    val ignoredFiles = stdout.split('\u0000').filter { it.isNotEmpty() }
    if (ignoredFiles.isEmpty()) return emptyList()
    return listOf(
        "[Rule 6: gitignored-kotlin-sources] The following Kotlin source files are ignored by .gitignore:\n" +
            ignoredFiles.joinToString("\n") { "  - $it" },
    )
}

fun checkNoCrossPluginImports(projectRoot: File): List<String> {
    val metaInfService = File(projectRoot, "src/main/resources/META-INF/services/luna.plugin.LunaPlugin")
    if (!metaInfService.exists()) return listOf("META-INF/services/luna.plugin.LunaPlugin must exist")

    val pluginPackages =
        metaInfService
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { fqcn ->
                val parts = fqcn.split(".")
                if (parts.size >= 3 && parts[0] == "luna") parts[1] else null
            }.toSet()
    if (pluginPackages.isEmpty()) return listOf("At least one first-party plugin must be registered")

    val sharedLibraryPrefixes =
        File(projectRoot, "libs")
            .takeIf { it.exists() }
            ?.walkTopDown()
            ?.filter { it.isFile && it.extension == "kt" }
            ?.mapNotNull { file ->
                file
                    .useLines { lines -> lines.firstOrNull { it.trim().startsWith("package ") } }
                    ?.trim()
                    ?.removePrefix("package ")
                    ?.trim()
            }?.toSet()
            ?: emptySet()

    val importRegex = Regex("""^import\s+luna\.([a-zA-Z0-9_]+)""")
    val violations = mutableListOf<String>()
    val appRoots = listOf(File(projectRoot, "src/main/kotlin"), File(projectRoot, "src/test/kotlin")).filter { it.exists() }

    appRoots.forEach { root ->
        pluginPackages.forEach { ownerPlugin ->
            val pluginDir = File(root, "luna/$ownerPlugin")
            if (!pluginDir.exists()) return@forEach

            pluginDir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
                val relPath = file.relativeTo(projectRoot).path.replace('\\', '/')
                file.useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        val trimmed = line.trim()
                        val match = importRegex.find(trimmed) ?: return@forEachIndexed
                        val importedPkg = match.groupValues[1]
                        val importedPath = trimmed.removePrefix("import ").trim()
                        val isSharedLibImport =
                            sharedLibraryPrefixes.any { prefix ->
                                importedPath == prefix || importedPath.startsWith("$prefix.")
                            }
                        if (importedPkg in pluginPackages && importedPkg != ownerPlugin && !isSharedLibImport) {
                            violations.add(
                                "[Rule 7: no-cross-plugin-imports] $relPath:${index + 1}: " +
                                    "Plugin '$ownerPlugin' must not import sibling plugin '$importedPkg' ($trimmed). " +
                                    "Extract shared logic into 'luna.core' instead.",
                            )
                        }
                    }
                }
            }
        }
    }
    return violations
}

fun checkTestSuiteLineLimit(projectRoot: File): List<String> {
    val maxLines = 600
    val testRoots = findKotlinSourceRoots(projectRoot, "test")
    if (testRoots.isEmpty()) return listOf("At least one src/test/kotlin directory must exist")

    val violations = mutableListOf<String>()
    testRoots.forEach { root ->
        root
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith("Test.kt") }
            .forEach { file ->
                val relPath = file.relativeTo(projectRoot).path.replace('\\', '/')
                val lineCount = file.useLines { it.count() }
                if (lineCount > maxLines) {
                    violations.add(
                        "[Rule 8: test-suite-line-limit] $relPath: $lineCount lines exceeds limit $maxLines. " +
                            "Split new concerns into a focused *Test.kt file per CODING_STANDARDS.md §5.",
                    )
                }
            }
    }
    return violations
}

fun checkDiagnosticBooleanAssertions(projectRoot: File): List<String> {
    val baselineFile = File(projectRoot, "gradle/coding-standards-assert-baseline.txt")
    if (!baselineFile.exists()) {
        return listOf(
            "[Rule 9: diagnostic-boolean-assertions] Baseline file missing: gradle/coding-standards-assert-baseline.txt",
        )
    }

    val baseline = mutableMapOf<String, Int>()
    baselineFile.useLines { lines ->
        lines.forEachIndexed { idx, raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachIndexed
            val sep = trimmed.lastIndexOf('=')
            if (sep <= 0 || sep == trimmed.length - 1) {
                throw GradleException(
                    "Invalid baseline entry at gradle/coding-standards-assert-baseline.txt:${idx + 1}: '$trimmed'",
                )
            }
            val path = trimmed.substring(0, sep).trim()
            val maxAllowed =
                trimmed.substring(sep + 1).trim().toIntOrNull()
                    ?: throw GradleException(
                        "Invalid count at gradle/coding-standards-assert-baseline.txt:${idx + 1}: '$trimmed'",
                    )
            baseline[path] = maxAllowed
        }
    }

    val testRoots = findKotlinSourceRoots(projectRoot, "test")
    if (testRoots.isEmpty()) return listOf("At least one src/test/kotlin directory must exist")

    val violations = mutableListOf<String>()
    testRoots.forEach { root ->
        root
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith("Test.kt") }
            .forEach { file ->
                val relPath = file.relativeTo(projectRoot).path.replace('\\', '/')
                val bareLines = findBareBooleanAssertions(file.readText())
                val maxAllowed = baseline[relPath] ?: 0
                if (bareLines.size > maxAllowed) {
                    violations.add(
                        "[Rule 9: diagnostic-boolean-assertions] $relPath: ${bareLines.size} bare assertTrue/assertFalse calls " +
                            "(max allowed: $maxAllowed) at lines ${bareLines.joinToString(", ", "[", "]")}. " +
                            "Pass a positional 2nd String message argument (e.g. assertTrue(cond, \"reason\") — " +
                            "works with both JUnit 5 and kotlin.test) to each new/edited assertion.",
                    )
                }
            }
    }
    return violations
}

fun checkMainSourceLineLimit(projectRoot: File): List<String> {
    val defaultMaxLines = 600
    val baselineFile = File(projectRoot, "gradle/coding-standards-main-lines-baseline.txt")
    if (!baselineFile.exists()) {
        return listOf(
            "[Rule 10: main-source-line-limit] Baseline file missing: gradle/coding-standards-main-lines-baseline.txt",
        )
    }

    val baseline = mutableMapOf<String, Int>()
    baselineFile.useLines { lines ->
        lines.forEachIndexed { idx, raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachIndexed
            val sep = trimmed.lastIndexOf('=')
            if (sep <= 0 || sep == trimmed.length - 1) {
                throw GradleException(
                    "Invalid baseline entry at gradle/coding-standards-main-lines-baseline.txt:${idx + 1}: '$trimmed'",
                )
            }
            val path = trimmed.substring(0, sep).trim()
            val maxAllowed =
                trimmed.substring(sep + 1).trim().toIntOrNull()
                    ?: throw GradleException(
                        "Invalid line count at gradle/coding-standards-main-lines-baseline.txt:${idx + 1}: '$trimmed'",
                    )
            baseline[path] = maxAllowed
        }
    }

    val mainRoots = findKotlinSourceRoots(projectRoot, "main")
    if (mainRoots.isEmpty()) return listOf("At least one src/main/kotlin directory must exist")

    val violations = mutableListOf<String>()
    val ratchetSlackLines = 20
    val seenBaselinePaths = mutableSetOf<String>()
    mainRoots.forEach { root ->
        root
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val relPath = file.relativeTo(projectRoot).path.replace('\\', '/')
                val lineCount = file.useLines { it.count() }
                val baselinedMax = baseline[relPath]
                val maxAllowed = baselinedMax ?: defaultMaxLines
                if (baselinedMax != null) {
                    seenBaselinePaths.add(relPath)
                }
                if (lineCount > maxAllowed) {
                    violations.add(
                        "[Rule 10: main-source-line-limit] $relPath: $lineCount lines exceeds limit $maxAllowed. " +
                            "Extract cohesive collaborators per CODING_STANDARDS.md §4.",
                    )
                } else if (baselinedMax != null && lineCount <= defaultMaxLines) {
                    violations.add(
                        "[Rule 10: main-source-line-limit] $relPath: $lineCount lines is now <= $defaultMaxLines. " +
                            "Remove its obsolete entry from gradle/coding-standards-main-lines-baseline.txt " +
                            "(or run './gradlew tightenCodingStandardsBaselines').",
                    )
                } else if (baselinedMax != null && lineCount <= baselinedMax - ratchetSlackLines) {
                    violations.add(
                        "[Rule 10: main-source-line-limit] $relPath: $lineCount lines dropped >= $ratchetSlackLines below baseline $baselinedMax. " +
                            "Tighten its entry in gradle/coding-standards-main-lines-baseline.txt to $lineCount " +
                            "(or run './gradlew tightenCodingStandardsBaselines').",
                    )
                }
            }
    }
    (baseline.keys - seenBaselinePaths).sorted().forEach { missingPath ->
        violations.add(
            "[Rule 10: main-source-line-limit] Baseline entry '$missingPath' no longer exists. " +
                "Remove it from gradle/coding-standards-main-lines-baseline.txt " +
                "(or run './gradlew tightenCodingStandardsBaselines').",
        )
    }
    return violations
}

fun checkLoggerClassMatch(projectRoot: File): List<String> {
    val mainRoots = findKotlinSourceRoots(projectRoot, "main")
    if (mainRoots.isEmpty()) return listOf("At least one src/main/kotlin directory must exist")

    val violations = mutableListOf<String>()
    val loggerCallRegex = Regex("""LoggerFactory\.getLogger\s*\(\s*([A-Za-z0-9_.]+)::class\.java\s*\)""")

    mainRoots.forEach { root ->
        root
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                val relPath = file.relativeTo(projectRoot).path.replace('\\', '/')
                val fileStem = file.nameWithoutExtension
                for (match in loggerCallRegex.findAll(text)) {
                    val targetExpr = match.groupValues[1]
                    val simpleName = targetExpr.substringAfterLast('.')
                    val declaredInFile =
                        Regex("""\b(?:class|object|interface)\s+${Regex.escape(simpleName)}\b""")
                            .containsMatchIn(text)
                    if (!declaredInFile && simpleName != fileStem) {
                        val lineNumber = text.substring(0, match.range.first).count { it == '\n' } + 1
                        violations.add(
                            "[Rule 11: logger-class-match] $relPath:$lineNumber: " +
                                "LoggerFactory.getLogger($targetExpr::class.java) does not match any type declared in this file. " +
                                "Attribute loggers to their own class/object per CODING_STANDARDS.md §5.",
                        )
                    }
                }
            }
    }
    return violations
}

val codingStandardsRoot = project.projectDir.canonicalFile

fun stripCommentsAndStringsForParamScan(text: String): String {
    val sb = StringBuilder(text.length)
    var i = 0
    val n = text.length
    while (i < n) {
        val c = text[i]
        when {
            c == '"' || c == '\'' -> {
                val (nextIdx, _) = skipStringOrChar(text, i, 1)
                for (k in i until nextIdx) {
                    sb.append(if (text[k] == '\n') '\n' else ' ')
                }
                i = nextIdx
            }
            text.startsWith("//", i) || text.startsWith("/*", i) -> {
                val (nextIdx, _) = skipWsAndComments(text, i, 1)
                for (k in i until nextIdx) {
                    sb.append(if (text[k] == '\n') '\n' else ' ')
                }
                i = nextIdx
            }
            else -> {
                sb.append(c)
                i++
            }
        }
    }
    return sb.toString()
}

fun countTopLevelParameters(paramBlock: String): Int {
    var depthParen = 0
    var depthBracket = 0
    var depthBrace = 0
    var depthAngle = 0
    var count = 0
    var hasToken = false
    for (ch in paramBlock) {
        when (ch) {
            '(' -> {
                depthParen++
                hasToken = true
            }
            ')' -> {
                depthParen--
                hasToken = true
            }
            '[' -> {
                depthBracket++
                hasToken = true
            }
            ']' -> {
                depthBracket--
                hasToken = true
            }
            '{' -> {
                depthBrace++
                hasToken = true
            }
            '}' -> {
                depthBrace--
                hasToken = true
            }
            '<' -> {
                depthAngle++
                hasToken = true
            }
            '>' -> {
                depthAngle = maxOf(0, depthAngle - 1)
                hasToken = true
            }
            ',' -> {
                if (depthParen == 0 && depthBracket == 0 && depthBrace == 0 && depthAngle == 0) {
                    if (hasToken) count++
                    hasToken = false
                }
            }
            else -> {
                if (!ch.isWhitespace()) hasToken = true
            }
        }
    }
    if (hasToken) count++
    return count
}

data class ParamCountDeclaration(
    val key: String,
    val relPath: String,
    val line: Int,
    val kind: String,
    val name: String,
    val paramCount: Int,
)

fun scanProductionParameterCounts(projectRoot: File): List<ParamCountDeclaration> {
    val mainRoots = findKotlinSourceRoots(projectRoot, "main")
    val declRegex =
        Regex(
            """(?m)^[ \t]*(?:(?:public|internal|private|protected|open|abstract|sealed|final|override|suspend|inline|tailrec|operator|infix|external|actual|expect|inner|value|enum|annotation|data)\s+)*(class|fun)\s+(?:<[^>]+>\s*)?([A-Za-z_][A-Za-z0-9_.]*)\s*(?:<[^>]+>\s*)?(?:(?:private|internal|protected|public)\s+constructor\s*)?\(""",
        )
    val dataClassRegex = Regex("""\bdata\s+class\b""")
    val results = mutableListOf<ParamCountDeclaration>()

    mainRoots.forEach { root ->
        root
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val relPath = file.relativeTo(projectRoot).path.replace('\\', '/')
                val clean = stripCommentsAndStringsForParamScan(file.readText())
                for (match in declRegex.findAll(clean)) {
                    val prefix = clean.substring(match.range.first, match.range.last + 1)
                    if (dataClassRegex.containsMatchIn(prefix)) continue
                    val kind = match.groupValues[1]
                    val name = match.groupValues[2]
                    var j = match.range.last + 1
                    var depth = 1
                    while (j < clean.length && depth > 0) {
                        when (clean[j]) {
                            '(' -> depth++
                            ')' -> depth--
                        }
                        j++
                    }
                    if (depth != 0) continue
                    val paramBlock = clean.substring(match.range.last + 1, j - 1)
                    val paramCount = countTopLevelParameters(paramBlock)
                    val line = clean.substring(0, match.range.first).count { it == '\n' } + 1
                    results.add(
                        ParamCountDeclaration(
                            key = "$relPath#$name",
                            relPath = relPath,
                            line = line,
                            kind = kind,
                            name = name,
                            paramCount = paramCount,
                        ),
                    )
                }
            }
    }
    return results
}

fun defaultMaxParamsForKind(kind: String): Int = if (kind == "fun") 8 else 10

fun checkParameterCountLimit(projectRoot: File): List<String> {
    val baselineFile = File(projectRoot, "gradle/coding-standards-param-count-baseline.txt")
    if (!baselineFile.exists()) {
        return listOf(
            "[Rule 12: parameter-count-limit] Baseline file missing: gradle/coding-standards-param-count-baseline.txt",
        )
    }

    val baseline = mutableMapOf<String, Int>()
    baselineFile.useLines { lines ->
        lines.forEachIndexed { idx, raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachIndexed
            val sep = trimmed.lastIndexOf('=')
            if (sep <= 0 || sep == trimmed.length - 1) {
                throw GradleException(
                    "Invalid baseline entry at gradle/coding-standards-param-count-baseline.txt:${idx + 1}: '$trimmed'",
                )
            }
            val key = trimmed.substring(0, sep).trim()
            val maxAllowed =
                trimmed.substring(sep + 1).trim().toIntOrNull()
                    ?: throw GradleException(
                        "Invalid parameter count at gradle/coding-standards-param-count-baseline.txt:${idx + 1}: '$trimmed'",
                    )
            baseline[key] = maxAllowed
        }
    }

    val declarations = scanProductionParameterCounts(projectRoot)
    val maxDeclByKey =
        declarations.groupBy { it.key }.mapValues { (_, list) -> list.maxBy { it.paramCount } }
    val violations = mutableListOf<String>()

    declarations.forEach { decl ->
        val defaultMaxParams = defaultMaxParamsForKind(decl.kind)
        val baselinedMax = baseline[decl.key]
        val maxAllowed = baselinedMax ?: defaultMaxParams
        if (decl.paramCount > maxAllowed) {
            violations.add(
                "[Rule 12: parameter-count-limit] ${decl.relPath}:${decl.line} (${decl.kind} ${decl.name}): " +
                    "${decl.paramCount} parameters exceeds limit $maxAllowed. " +
                    "Bundle cohesive collaborators or request objects per CODING_STANDARDS.md §4.",
            )
        }
    }

    baseline.forEach { (key, baselinedMax) ->
        val currentDecl = maxDeclByKey[key]
        val currentMax = currentDecl?.paramCount
        val defaultMaxParams = currentDecl?.let { defaultMaxParamsForKind(it.kind) } ?: 10
        when {
            currentMax == null -> {
                violations.add(
                    "[Rule 12: parameter-count-limit] Baseline entry '$key' no longer exists. " +
                        "Remove it from gradle/coding-standards-param-count-baseline.txt " +
                        "(or run './gradlew tightenCodingStandardsBaselines').",
                )
            }
            currentMax <= defaultMaxParams -> {
                violations.add(
                    "[Rule 12: parameter-count-limit] '$key' now has $currentMax parameters (<= $defaultMaxParams). " +
                        "Remove its obsolete entry from gradle/coding-standards-param-count-baseline.txt " +
                        "(or run './gradlew tightenCodingStandardsBaselines').",
                )
            }
            currentMax < baselinedMax -> {
                violations.add(
                    "[Rule 12: parameter-count-limit] '$key' dropped from $baselinedMax to $currentMax parameters. " +
                        "Tighten its entry in gradle/coding-standards-param-count-baseline.txt to $currentMax " +
                        "(or run './gradlew tightenCodingStandardsBaselines').",
                )
            }
        }
    }

    return violations
}

fun checkSqliteJsonCoerceInputValues(projectRoot: File): List<String> {
    val mainRoots = findKotlinSourceRoots(projectRoot, "main")
    val violations = mutableListOf<String>()
    val jsonBuilderRegex = Regex("""\bJson\s*\{""")
    val coerceInputValuesRegex = Regex("""\bcoerceInputValues\s*=\s*true\b""")

    mainRoots.forEach { mainRoot ->
        mainRoot
            .walkTopDown()
            .filter { it.isFile && it.name.startsWith("Sqlite") && it.name.endsWith("Store.kt") }
            .forEach { file ->
                val relPath = file.relativeTo(projectRoot).path.replace('\\', '/')
                val text = file.readText()
                jsonBuilderRegex.findAll(text).forEach { match ->
                    val openBraceIdx = match.range.last
                    val remaining = text.substring(openBraceIdx)
                    val closingOffset = findClosingBraceIndex(remaining)
                    if (closingOffset >= 0) {
                        val blockBody = remaining.substring(0, closingOffset + 1)
                        if (!coerceInputValuesRegex.containsMatchIn(blockBody)) {
                            val lineNumber = text.substring(0, match.range.first).count { it == '\n' } + 1
                            violations.add(
                                "[Rule 13: sqlite-json-coerce-input-values] $relPath:$lineNumber: " +
                                    "Persisted SQLite Json configuration must set 'coerceInputValues = true' so unknown " +
                                    "enum values in legacy rows gracefully fall back to defaults instead of throwing SerializationException.",
                            )
                        }
                    }
                }
            }
    }

    return violations
}

tasks.register("lintCodingStandards") {
    group = "verification"
    description = "Enforces mechanical CODING_STANDARDS.md rules without compiling Kotlin sources."

    doLast {
        val violations =
            buildList {
                addAll(checkBoundedSingletonCollections(codingStandardsRoot))
                addAll(checkBrittleServiceTestAssertions(codingStandardsRoot))
                addAll(checkJvmLegalTestNames(codingStandardsRoot))
                addAll(checkDockerfileSubprojectParity(codingStandardsRoot))
                addAll(checkEnvExampleParity(codingStandardsRoot))
                addAll(checkNoGitignoredKotlinSources(codingStandardsRoot))
                addAll(checkNoCrossPluginImports(codingStandardsRoot))
                addAll(checkTestSuiteLineLimit(codingStandardsRoot))
                addAll(checkDiagnosticBooleanAssertions(codingStandardsRoot))
                addAll(checkMainSourceLineLimit(codingStandardsRoot))
                addAll(checkLoggerClassMatch(codingStandardsRoot))
                addAll(checkParameterCountLimit(codingStandardsRoot))
                addAll(checkSqliteJsonCoerceInputValues(codingStandardsRoot))
            }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Coding standards violations found (${violations.size}):\n\n" +
                    violations.joinToString("\n\n"),
            )
        }
        logger.lifecycle("lintCodingStandards: all 13 rules passed.")
    }
}

tasks.register("tightenCodingStandardsBaselines") {
    group = "verification"
    description = "Auto-tightens shrunk or obsolete Rule 10 and Rule 12 baseline entries (never increases limits)."

    doLast {
        val defaultMaxLines = 600
        val baselineFile = File(codingStandardsRoot, "gradle/coding-standards-main-lines-baseline.txt")
        if (!baselineFile.exists()) {
            throw GradleException("Baseline file missing: gradle/coding-standards-main-lines-baseline.txt")
        }

        val headerLines = mutableListOf<String>()
        val updatedEntries = linkedMapOf<String, Int>()
        val changes = mutableListOf<String>()

        baselineFile.readLines().forEachIndexed { idx, raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                if (updatedEntries.isEmpty()) headerLines.add(raw)
                return@forEachIndexed
            }
            val sep = trimmed.lastIndexOf('=')
            if (sep <= 0 || sep == trimmed.length - 1) {
                throw GradleException(
                    "Invalid baseline entry at gradle/coding-standards-main-lines-baseline.txt:${idx + 1}: '$trimmed'",
                )
            }
            val relPath = trimmed.substring(0, sep).trim()
            val baselinedMax =
                trimmed.substring(sep + 1).trim().toIntOrNull()
                    ?: throw GradleException(
                        "Invalid line count at gradle/coding-standards-main-lines-baseline.txt:${idx + 1}: '$trimmed'",
                    )
            val file = File(codingStandardsRoot, relPath)
            if (!file.exists()) {
                changes.add("removed missing $relPath (was $baselinedMax)")
                return@forEachIndexed
            }
            val lineCount = file.useLines { it.count() }
            when {
                lineCount <= defaultMaxLines -> {
                    changes.add("removed $relPath ($lineCount <= $defaultMaxLines, was $baselinedMax)")
                }
                lineCount < baselinedMax -> {
                    updatedEntries[relPath] = lineCount
                    changes.add("tightened $relPath: $baselinedMax -> $lineCount")
                }
                else -> {
                    updatedEntries[relPath] = baselinedMax
                }
            }
        }

        if (changes.isEmpty()) {
            logger.lifecycle("tightenCodingStandardsBaselines: no Rule 10 baseline changes needed.")
        } else {
            val content =
                buildString {
                    headerLines.forEach { appendLine(it) }
                    updatedEntries.entries.sortedBy { it.key }.forEach { (path, count) ->
                        appendLine("$path=$count")
                    }
                }
            baselineFile.writeText(content)
            logger.lifecycle("tightenCodingStandardsBaselines: updated ${changes.size} Rule 10 entry(ies):\n  - " + changes.joinToString("\n  - "))
        }

        // Tighten Rule 12 parameter count baseline
        val paramBaselineFile = File(codingStandardsRoot, "gradle/coding-standards-param-count-baseline.txt")
        if (!paramBaselineFile.exists()) {
            throw GradleException("Baseline file missing: gradle/coding-standards-param-count-baseline.txt")
        }
        val maxDeclByKey =
            scanProductionParameterCounts(codingStandardsRoot)
                .groupBy { it.key }
                .mapValues { (_, list) -> list.maxBy { it.paramCount } }
        val paramHeaderLines = mutableListOf<String>()
        val paramUpdatedEntries = linkedMapOf<String, Int>()
        val paramChanges = mutableListOf<String>()
        paramBaselineFile.readLines().forEachIndexed { idx, raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                if (paramUpdatedEntries.isEmpty()) paramHeaderLines.add(raw)
                return@forEachIndexed
            }
            val sep = trimmed.lastIndexOf('=')
            if (sep <= 0 || sep == trimmed.length - 1) {
                throw GradleException(
                    "Invalid baseline entry at gradle/coding-standards-param-count-baseline.txt:${idx + 1}: '$trimmed'",
                )
            }
            val key = trimmed.substring(0, sep).trim()
            val baselinedMax =
                trimmed.substring(sep + 1).trim().toIntOrNull()
                    ?: throw GradleException(
                        "Invalid parameter count at gradle/coding-standards-param-count-baseline.txt:${idx + 1}: '$trimmed'",
                    )
            val currentDecl = maxDeclByKey[key]
            val currentMax = currentDecl?.paramCount
            val defaultMaxParams = currentDecl?.let { defaultMaxParamsForKind(it.kind) } ?: 10
            when {
                currentMax == null -> {
                    paramChanges.add("removed missing $key (was $baselinedMax)")
                }
                currentMax <= defaultMaxParams -> {
                    paramChanges.add("removed $key ($currentMax <= $defaultMaxParams, was $baselinedMax)")
                }
                currentMax < baselinedMax -> {
                    paramUpdatedEntries[key] = currentMax
                    paramChanges.add("tightened $key: $baselinedMax -> $currentMax")
                }
                else -> {
                    paramUpdatedEntries[key] = baselinedMax
                }
            }
        }
        if (paramChanges.isEmpty()) {
            logger.lifecycle("tightenCodingStandardsBaselines: no Rule 12 baseline changes needed.")
        } else {
            val content =
                buildString {
                    paramHeaderLines.forEach { appendLine(it) }
                    paramUpdatedEntries.entries.sortedBy { it.key }.forEach { (key, count) ->
                        appendLine("$key=$count")
                    }
                }
            paramBaselineFile.writeText(content)
            logger.lifecycle(
                "tightenCodingStandardsBaselines: updated ${paramChanges.size} Rule 12 entry(ies):\n  - " + paramChanges.joinToString("\n  - "),
            )
        }
    }
}
