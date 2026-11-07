plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "luna"
version = "2.1.0"

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}

kotlin {
    jvmToolchain(17)
}

tasks.processResources {
    from(rootProject.file(".env.example"))
}

tasks.test {
    useJUnitPlatform()
}
