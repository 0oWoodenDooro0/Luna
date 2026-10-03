plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "Luna"

val localSocialPeek = file("../SocialPeek")

val forceRemote = providers.gradleProperty("remote").isPresent ||
    providers.gradleProperty("useLocalSocialPeek").orNull == "false" ||
    providers.environmentVariable("USE_LOCAL_SOCIAL_PEEK").orNull == "false"

val isLocal = !forceRemote && (
    localSocialPeek.exists() ||
    providers.gradleProperty("local").isPresent ||
    providers.gradleProperty("localSocialPeek").isPresent ||
    providers.gradleProperty("useLocalSocialPeek").orNull == "true" ||
    providers.environmentVariable("USE_LOCAL_SOCIAL_PEEK").orNull == "true" ||
    providers.environmentVariable("LOCAL_SOCIAL_PEEK").orNull == "true"
)

val useLocal = isLocal && localSocialPeek.exists()

if (useLocal) {
    logger.lifecycle(">> [SocialPeek] Using LOCAL relative project: ${localSocialPeek.path}")
    includeBuild(localSocialPeek) {
        dependencySubstitution {
            substitute(module("com.github.0oWoodenDooro0:SocialPeek")).using(project(":"))
        }
    }
} else {
    logger.lifecycle(">> [SocialPeek] Using REMOTE JitPack dependency")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url = uri("https://snapshots.kord.dev") }
        maven { url = uri("https://jitpack.io") }
    }
}
