plugins {
    kotlin("jvm") version "2.3.10"
}

group = "website.woodendoor"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.14.9")
    implementation("dev.kord:kord-core:0.18.0")
    implementation("org.slf4j:slf4j-simple:2.0.17")
    implementation("org.xerial:sqlite-jdbc:3.51.3.0")
    implementation("org.jetbrains.exposed:exposed-core:1.1.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.1.1")
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
        attributes["Main-Class"] = "website.woodendoor.MainKt"
    }

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    configurations["compileClasspath"].forEach { file: File ->
        from(zipTree(file.absoluteFile))
    }
}