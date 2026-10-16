plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

allprojects {
    group = rootProject.property("group") as String
    version = rootProject.property("version") as String
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(17)
        // The plugin runs on the Kotlin that Gradle embeds: 1.9 in Gradle 8.7, the oldest
        // supported. scarabuff-core and the CLI are bundled with it, so they target 1.9 too.
        compilerOptions {
            apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9)
            languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9)
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    repositories {
        mavenCentral()
        google()
    }
}
