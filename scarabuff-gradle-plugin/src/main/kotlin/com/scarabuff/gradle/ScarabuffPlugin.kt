package com.scarabuff.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Applies the `scarabuff { ... }` extension and a `generateScarabuffSources` task that runs
 * `flatc --kotlin` + the Kotlin coroutine generator, wiring the output into the `main` source
 * set.
 *
 * Requires `org.jetbrains.kotlin.jvm` to also be applied (source-set wiring for the generated
 * Kotlin sources is deferred until that plugin is present, so apply order doesn't matter).
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
            task.description = "Generates FlatBuffers + gRPC Kotlin coroutine sources from .fbs schemas"
        }

        project.plugins.withId("org.jetbrains.kotlin.jvm") {
            project.extensions.getByType(KotlinJvmProjectExtension::class.java)
                .sourceSets.getByName("main").kotlin.srcDir(extension.outputDir)
            project.tasks.named("compileKotlin") { it.dependsOn(generateTask) }
        }
    }
}
