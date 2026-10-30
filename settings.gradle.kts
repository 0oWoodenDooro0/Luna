plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "Luna"

include(":libs:socialpeek", ":libs:curtly")
project(":libs:socialpeek").projectDir = file("libs/socialpeek")
project(":libs:curtly").projectDir = file("libs/curtly")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        maven { url = uri("https://snapshots.kord.dev") }
        maven { url = uri("https://jitpack.io") }
    }
}
