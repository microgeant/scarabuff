package com.scarabuff.gradle

import com.scarabuff.core.Orchestrator
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Runs `flatc` against every `.fbs` file in [schemaDir] and generates a Kotlin coroutine wrapper
 * per `rpc_service` found, via [Orchestrator]. Registered by [ScarabuffPlugin] as
 * `generateScarabuffSources`.
 */
abstract class GenerateScarabuffSourcesTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemaDir: DirectoryProperty

    @get:Input
    abstract val flatc: Property<String>

    @get:Input
    abstract val kotlinPackageSuffix: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        Orchestrator.generate(
            schemaDir = schemaDir.get().asFile,
            outputDir = outputDir.get().asFile,
            flatc = flatc.get(),
            kotlinPackageSuffix = kotlinPackageSuffix.get(),
        )
    }
}
