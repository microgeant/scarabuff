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
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.truth)
    testImplementation(gradleTestKit())
}

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
