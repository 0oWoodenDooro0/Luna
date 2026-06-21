plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "Luna"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}