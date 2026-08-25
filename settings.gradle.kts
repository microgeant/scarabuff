rootProject.name = "scarabuff"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

include(":scarabuff-core")
include(":scarabuff-gradle-plugin")
include(":scarabuff-cli")
