plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.kotlinter)
}

subprojects {
    apply(plugin = "org.jmailen.kotlinter")
}

group = "luna"
version = "1.5.0"

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
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Curtly URL Shortener Library
    implementation(project(":libs:curtly"))

    // SocialPeek Social Post Resolver Library
    implementation(project(":libs:socialpeek"))
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("net.bytebuddy.experimental", "true")
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
