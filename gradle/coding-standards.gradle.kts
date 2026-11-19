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
    val illegalChars = listOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
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
                            "Add a 2nd message argument or trailing lambda { \"...\" } to each new/edited assertion.",
                    )
                }
            }
    }
    return violations
}

val codingStandardsRoot = project.projectDir.canonicalFile

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
            }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Coding standards violations found (${violations.size}):\n\n" +
                    violations.joinToString("\n\n"),
            )
        }
        logger.lifecycle("lintCodingStandards: all 9 rules passed.")
    }
}
