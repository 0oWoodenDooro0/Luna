plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "Luna"

val localSocialPeek = file("../SocialPeek")

val isRemote = providers.gradleProperty("remote").isPresent ||
    providers.gradleProperty("remoteSocialPeek").isPresent ||
    providers.gradleProperty("useLocalSocialPeek").orNull == "false"

val isLocal = providers.gradleProperty("local").isPresent ||
    providers.gradleProperty("localSocialPeek").isPresent ||
    providers.gradleProperty("useLocalSocialPeek").orNull == "true"

val useLocal = when {
    isRemote -> false
    isLocal -> true
    else -> localSocialPeek.exists()
}

if (useLocal && localSocialPeek.exists()) {
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
        maven { url = uri("https://jitpack.io") }
    }
}
