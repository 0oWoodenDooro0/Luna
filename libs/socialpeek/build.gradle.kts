plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    `maven-publish`
}

group = "com.github.0oWoodenDooro0"
version = "0.5.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.5.2"
val serializationVersion = "1.11.0"
val coroutinesVersion = "1.10.2"
val jsoupVersion = "1.21.1"
val junitVersion = "5.12.0"

dependencies {
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")

    // Ktor HTTP Client
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // HTML Parsing
    implementation("org.jsoup:jsoup:$jsoupVersion")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = "socialpeek"
            version = project.version.toString()

            pom {
                name.set("SocialPeek")
                description.set("A pure Kotlin multi-platform social media post metadata and media parser")
                url.set("https://github.com/0oWoodenDooro0/SocialPeek")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("0oWoodenDooro0")
                        name.set("0oWoodenDooro0")
                    }
                }
            }
        }
    }
}
