package com.scarabuff.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Applies the `scarabuff { ... }` extension and a `generateScarabuffSources` task that runs
 * `flatc` + the Kotlin coroutine generator, wiring both outputs into the `main` source set.
 *
 * Requires `org.jetbrains.kotlin.jvm` to also be applied (source-set wiring for the generated
 * Kotlin coroutine wrappers is deferred until that plugin is present, so apply order doesn't
 * matter).
 */
class ScarabuffPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("scarabuff", ScarabuffExtension::class.java)
        extension.schemaDir.convention(project.layout.projectDirectory.dir("src/main/fbs"))
        extension.outputDir.convention(project.layout.buildDirectory.dir("generated/source/scarabuff"))

        val generateTask = project.tasks.register("generateScarabuffSources", GenerateScarabuffSourcesTask::class.java) { task ->
            task.schemaDir.set(extension.schemaDir)
            task.flatc.set(extension.flatc)
            task.kotlinPackageSuffix.set(extension.kotlinPackageSuffix)
            task.outputDir.set(extension.outputDir)
            task.group = "scarabuff"
            task.description = "Generates FlatBuffers Java + gRPC Kotlin coroutine sources from .fbs schemas"
        }

        val javaOutputDir = extension.outputDir.dir("java")
        val kotlinOutputDir = extension.outputDir.dir("kotlin")

        project.plugins.withId("java") {
            project.extensions.getByType(JavaPluginExtension::class.java)
                .sourceSets.getByName("main").java.srcDir(javaOutputDir)
            project.tasks.named("compileJava") { it.dependsOn(generateTask) }
        }

        project.plugins.withId("org.jetbrains.kotlin.jvm") {
            project.extensions.getByType(KotlinJvmProjectExtension::class.java)
                .sourceSets.getByName("main").kotlin.srcDir(kotlinOutputDir)
            project.tasks.named("compileKotlin") { it.dependsOn(generateTask) }
        }
    }
}
