import java.time.Duration

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.kotlinter)
}

allprojects {
    tasks.withType<Test> {
        timeout.set(Duration.ofSeconds(60))
    }
}

subprojects {
    apply(plugin = "org.jmailen.kotlinter")

    tasks.withType<Test> {
        filter {
            isFailOnNoMatchingTests = false
        }
    }
}

group = "luna"
version = "2.1.0"

application {
    mainClass.set("luna.core.MainKt")
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.mockk)
    testImplementation(libs.h2)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    implementation(libs.kord.core)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    
    // Fix CVE-2026-29062 in transitive jackson-core dependency from logstash-logback-encoder
    implementation(libs.jackson.core)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.sqlite.jdbc)
    implementation(libs.snakeyaml)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.sessions)

    // Serialization & Ktor Client
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.html.jvm)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // SocialPeek Social Post Resolver Library
    implementation(project(":libs:socialpeek"))
    implementation(project(":libs:config"))
    implementation(project(":libs:mcp-data"))
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("net.bytebuddy.experimental", "true")
    filter {
        isFailOnNoMatchingTests = false
    }
}

tasks.processResources {
    from(rootProject.file(".env.example"))
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "luna.core.MainKt"
    }

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(configurations.runtimeClasspath.map { configuration ->
        configuration.map { if (it.isDirectory) it else zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}

tasks.register("installGitHooks") {
    description = "Configures git core.hooksPath to .githooks"
    group = "help"
    onlyIf {
        val hooksDir = file(".githooks")
        if (!hooksDir.exists() || !file(".git").exists()) {
            false
        } else {
            val currentHooksPath =
                runCatching {
                    ProcessBuilder("git", "config", "--get", "core.hooksPath")
                        .directory(projectDir)
                        .start()
                        .let { proc ->
                            proc.inputStream.bufferedReader().readText().trim().also { proc.waitFor() }
                        }
                }.getOrDefault("")
            currentHooksPath != ".githooks"
        }
    }
    doLast {
        ProcessBuilder("git", "config", "core.hooksPath", ".githooks")
            .directory(projectDir)
            .start()
            .waitFor()
        listOf("pre-commit", "pre-push").forEach { hookName ->
            val hookFile = file(".githooks/$hookName")
            if (hookFile.exists() && !hookFile.canExecute()) {
                hookFile.setExecutable(true)
            }
        }
        logger.lifecycle("Configured git core.hooksPath to .githooks")
    }
}

tasks.named("compileKotlin") {
    dependsOn("installGitHooks")
}
