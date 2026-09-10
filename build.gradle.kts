plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    application
    id("org.jmailen.kotlinter") version "5.4.2"
}

group = "luna"
version = "1.2"

application {
    mainClass.set("luna.core.MainKt")
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("com.h2database:h2:2.3.232")
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.5.0")
    implementation("dev.kord:kord-core:0.18.0")
    implementation("ch.qos.logback:logback-classic:1.5.38")
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")
    
    // Fix CVE-2026-29062 in transitive jackson-core dependency from logstash-logback-encoder
    implementation("tools.jackson.core:jackson-core:3.1.0")
    implementation("tools.jackson.core:jackson-databind:3.1.0")
    implementation("org.xerial:sqlite-jdbc:3.51.3.0")
    implementation("org.jetbrains.exposed:exposed-core:1.1.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.1.1")
    implementation("org.yaml:snakeyaml:2.4")
    
    // Ktor Server
    implementation("io.ktor:ktor-server-core-jvm:3.5.0")
    implementation("io.ktor:ktor-server-netty-jvm:3.5.0")
    implementation("io.ktor:ktor-server-sessions-jvm:3.5.0")

    // Curtly URL Shortener Library
    implementation("com.github.0oWoodenDooro0:Curtly:3.1.0")

    // SocialPeek Social Post Resolver Library
    implementation("com.github.0oWoodenDooro0:SocialPeek:v0.1.0")
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
    })
}
