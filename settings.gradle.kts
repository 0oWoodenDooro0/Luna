plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "Luna"

include(":libs:socialpeek")
project(":libs:socialpeek").projectDir = file("libs/socialpeek")
include(":libs:config")
project(":libs:config").projectDir = file("libs/config")
include(":libs:mcp-data")
project(":libs:mcp-data").projectDir = file("libs/mcp-data")
include("mcp-server")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        maven { url = uri("https://snapshots.kord.dev") }
    }
}
