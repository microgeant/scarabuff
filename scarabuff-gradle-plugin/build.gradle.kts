import java.time.Duration

plugins {
    `java-gradle-plugin`
    alias(libs.plugins.plugin.publish)
    alias(libs.plugins.shadow)
}

dependencies {
    // scarabuff-core isn't published on its own, so it's bundled into the plugin jar (see
    // shadowJar below). Its external dependencies are declared on the `shadow` configuration
    // instead, so they stay ordinary entries in the published POM rather than being bundled.
    implementation(project(":scarabuff-core"))
    shadow(libs.kotlinpoet)
    shadow(libs.flatbuffers.java)
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.truth)
    testImplementation(gradleTestKit())
}

// The runtime libraries the plugin adds to consuming projects (see ScarabuffPlugin), written from
// the version catalog so the plugin always adds exactly the versions this repo builds and tests.
val runtimeDependencies: List<String> = listOf(
    libs.flatbuffers.java.asProvider(),
    libs.flatbuffers.java.grpc,
    libs.grpc.stub,
    libs.grpc.kotlin.stub,
    libs.kotlinx.coroutines.core,
).map { lib -> lib.get().let { "${it.module}:${it.versionConstraint.requiredVersion}" } }

val generateRuntimeDependencies by tasks.registering {
    val coordinates = runtimeDependencies
    val outputDir = layout.buildDirectory.dir("generated/resources/scarabuff")
    inputs.property("coordinates", coordinates)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("com/scarabuff/gradle/runtime-dependencies.txt").asFile
        file.parentFile.mkdirs()
        file.writeText(coordinates.joinToString("\n", postfix = "\n"))
    }
}
sourceSets.main { resources.srcDir(generateRuntimeDependencies) }

// plugin-publish publishes the shadow jar instead of the plain jar when shadow is applied. An
// empty classifier makes it the main artifact.
tasks.shadowJar {
    archiveClassifier.set("")
    dependencies {
        include(project(":scarabuff-core"))
    }
}

gradlePlugin {
    website.set("https://github.com/microgeant/scarabuff")
    vcsUrl.set("https://github.com/microgeant/scarabuff.git")
    plugins {
        create("scarabuff") {
            id = "io.github.microgeant.scarabuff"
            implementationClass = "com.scarabuff.gradle.ScarabuffPlugin"
            displayName = "scarabuff"
            description = "Generates FlatBuffers + gRPC Kotlin coroutine boilerplate from .fbs " +
                "rpc_service definitions. Requires a flatc binary matching your flatbuffers-java version."
            tags.set(listOf("flatbuffers", "grpc", "kotlin", "coroutines", "codegen"))
        }
    }
}

// Functional tests: publish the real plugin (shadow jar + POM, as it goes to the Portal) to a local
// repository, then build and run a copy of example/ against it on several Gradle and Kotlin
// versions. Slow (downloads each Gradle version once), so it isn't part of `check`.
publishing {
    repositories {
        maven {
            name = "functionalTest"
            url = uri(layout.buildDirectory.dir("functional-test-repo"))
        }
    }
}

val functionalTest by sourceSets.creating
configurations[functionalTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())

tasks.register<Test>("functionalTest") {
    description = "Builds example/ against the published plugin on each supported Gradle/Kotlin version."
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    dependsOn("publishAllPublicationsToFunctionalTestRepository")
    val repo = layout.buildDirectory.dir("functional-test-repo")
    val example = rootProject.layout.projectDirectory.dir("example")
    inputs.dir(repo)
    inputs.dir(example.dir("src"))
    systemProperty("scarabuff.repo", repo.get().asFile.absolutePath)
    systemProperty("scarabuff.version", project.version.toString())
    systemProperty("scarabuff.example", example.asFile.absolutePath)
    // The fixture builds run in their own Gradle daemons; give the test JVM time for them.
    timeout.set(Duration.ofMinutes(30))
}