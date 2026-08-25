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
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    repositories {
        mavenCentral()
        google()
    }
}
