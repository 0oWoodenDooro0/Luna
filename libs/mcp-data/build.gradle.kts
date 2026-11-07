plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "luna"
version = "2.1.0"

dependencies {
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.sqlite.jdbc)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.h2)
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
