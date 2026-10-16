package com.scarabuff.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.Provider
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
        extension.flatc.convention(project.providers.environmentVariable("FLATC"))
        extension.flatbuffersVersion.convention(RUNTIME_DEPENDENCIES.first { it.startsWith("$FLATBUFFERS_JAVA:") }.substringAfterLast(':'))

        val generateTask = project.tasks.register("generateScarabuffSources", GenerateScarabuffSourcesTask::class.java) { task ->
            task.schemaDir.set(extension.schemaDir)
            task.flatc.set(extension.flatc)
            task.flatcVersion.set(extension.flatcVersion)
            task.flatcCacheDir.set(project.gradle.gradleUserHomeDir.resolve("caches/scarabuff/flatc"))
            task.flatcIdentity.set(flatcIdentity(project, extension))
            task.kotlinPackageSuffix.set(extension.kotlinPackageSuffix)
            task.generateBuilders.set(extension.generateBuilders)
            task.outputDir.set(extension.outputDir)
            task.group = "scarabuff"
            task.description = "Generates FlatBuffers + gRPC Kotlin coroutine sources from .fbs schemas"
        }

        project.plugins.withId("org.jetbrains.kotlin.jvm") {
            // `api`, not `implementation`: the generated classes expose these libraries' types, so
            // a module depending on the schema module needs them on its compile classpath too.
            project.configurations.named("api") { api ->
                api.dependencies.addAllLater(
                    extension.addRuntimeDependencies.map { add ->
                        if (!add) return@map emptyList()
                        RUNTIME_DEPENDENCIES.map { notation ->
                            // flatbuffers-java follows flatbuffersVersion; the rest are fixed.
                            if (notation.startsWith("$FLATBUFFERS_JAVA:")) {
                                project.dependencies.create("$FLATBUFFERS_JAVA:${extension.flatbuffersVersion.get()}")
                            } else {
                                project.dependencies.create(notation)
                            }
                        }
                    }
                )
            }
            extension.flatcVersion.convention(flatbuffersJavaVersion(project))
            project.extensions.getByType(KotlinJvmProjectExtension::class.java)
                .sourceSets.getByName("main").kotlin.srcDir(extension.outputDir)
            project.tasks.named("compileKotlin") { it.dependsOn(generateTask) }
        }
    }

    private companion object {
        const val FLATBUFFERS_JAVA = "com.google.flatbuffers:flatbuffers-java"

        /** Written at build time from the version catalog; see scarabuff-gradle-plugin/build.gradle.kts. */
        val RUNTIME_DEPENDENCIES: List<String> =
            ScarabuffPlugin::class.java.getResource("runtime-dependencies.txt")!!
                .readText().lines().filter { it.isNotBlank() }
    }

    /** `managed:<version>` or `custom:<flatc --version output>`; see [GenerateScarabuffSourcesTask.flatcIdentity]. */
    private fun flatcIdentity(project: Project, extension: ScarabuffExtension): Provider<String> {
        val custom = extension.flatc.flatMap { flatc ->
            project.providers.exec { it.commandLine(flatc, "--version") }
                .standardOutput.asText.map { "custom:${it.trim()}" }
        }
        val managed = extension.flatcVersion.map { "managed:$it" }
        // "unresolved" lets the task run and fail with an explanation instead of Gradle's generic
        // "property has no value".
        return custom.orElse(managed).orElse("unresolved")
    }

    /** The `com.google.flatbuffers:flatbuffers-java` version the main code compiles against, if any. */
    private fun flatbuffersJavaVersion(project: Project): Provider<String> =
        project.configurations.named("compileClasspath")
            .flatMap { it.incoming.resolutionResult.rootComponent }
            // provider { null } is an absent value; map can't express that from Kotlin.
            .flatMap { root -> project.provider { findModuleVersion(root, "com.google.flatbuffers", "flatbuffers-java") } }

    private fun findModuleVersion(root: ResolvedComponentResult, group: String, module: String): String? {
        val seen = mutableSetOf<ResolvedComponentResult>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            if (!seen.add(component)) continue
            val id = component.id
            if (id is ModuleComponentIdentifier && id.group == group && id.module == module) return id.version
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue += it.selected }
        }
        return null
    }
}
