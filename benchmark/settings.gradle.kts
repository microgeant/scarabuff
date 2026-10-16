rootProject.name = "scarabuff-benchmark"

pluginManagement {
    // Benchmarks this checkout of scarabuff, not a published version.
    includeBuild("..")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
