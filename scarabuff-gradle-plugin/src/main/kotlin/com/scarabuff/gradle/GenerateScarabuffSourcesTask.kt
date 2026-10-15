package com.scarabuff.gradle

import com.scarabuff.core.FlatcInstaller
import com.scarabuff.core.Orchestrator
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
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

    /** A user-supplied flatc; when absent, the managed flatc for [flatcVersion] is used. */
    @get:Internal
    abstract val flatc: Property<String>

    @get:Internal
    abstract val flatcVersion: Property<String>

    /** Where managed flatc binaries are cached, shared by every build on the machine. */
    @get:Internal
    abstract val flatcCacheDir: DirectoryProperty

    /**
     * Which flatc this run uses, as far as up-to-date checks are concerned: the managed version,
     * or a user-supplied binary's `--version` output. Without it, swapping the binary at the same
     * path wouldn't regenerate anything.
     */
    @get:Input
    abstract val flatcIdentity: Property<String>

    @get:Input
    abstract val kotlinPackageSuffix: Property<String>

    @get:Input
    abstract val generateBuilders: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val result = Orchestrator.generate(
            schemaDir = schemaDir.get().asFile,
            outputDir = outputDir.get().asFile,
            flatc = flatc.orNull ?: managedFlatc(),
            kotlinPackageSuffix = kotlinPackageSuffix.get(),
            generateBuilders = generateBuilders.get(),
        )
        for ((table, reason) in result.tablesWithoutBuilders) {
            logger.lifecycle("scarabuff: no build() for $table yet: it has a $reason (fromBytes is still generated)")
        }
    }

    private fun managedFlatc(): String {
        val version = flatcVersion.orNull ?: throw GradleException(
            "scarabuff can't tell which flatc to use: com.google.flatbuffers:flatbuffers-java isn't on the " +
                "compile classpath. Add it as a dependency (the generated code needs it anyway), or set " +
                "scarabuff { flatcVersion.set(\"25.2.10\") } or scarabuff { flatc.set(\"/path/to/flatc\") }."
        )
        return try {
            FlatcInstaller.install(version, flatcCacheDir.get().asFile).absolutePath
        } catch (e: IllegalArgumentException) {
            throw GradleException(e.message!!, e)
        }
    }
}
