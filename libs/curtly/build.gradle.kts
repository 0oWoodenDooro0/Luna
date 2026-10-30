plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

group = "com.github.0oWoodenDooro0.Luna"
version = rootProject.version.toString()

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.html.jvm)
    implementation(libs.logback.classic)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)

    testImplementation(kotlin("test"))
    testImplementation(libs.h2)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = "curtly"
            version = project.version.toString()

            pom {
                name.set("Curtly")
                description.set("A lightweight Kotlin library for URL shortening, custom key generation, expiration handling, and click-limit tracking")
                url.set("https://github.com/0oWoodenDooro0/Luna")
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
