package com.scarabuff.gradle

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.Variant
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/**
 * Wires scarabuff into an Android library or application module, through the Android Gradle
 * plugin's variant API, the one AGP supports for code generators. Each variant gets its own
 * `generate<Variant>ScarabuffSources` task, registered as generated sources: AGP picks the output
 * directory, compiles it with the variant's Kotlin (the Kotlin Android plugin, or AGP 9's built-in
 * Kotlin), and shows it in the IDE. `generateScarabuffSources` runs them all.
 *
 * Only loaded once an Android plugin is applied, so plain JVM projects never need AGP's classes.
 */
internal object AndroidSupport {

    fun configure(project: Project, configureTask: (GenerateScarabuffSourcesTask) -> Unit) {
        val all = project.tasks.register("generateScarabuffSources") { task ->
            task.group = "scarabuff"
            task.description = "Generates FlatBuffers + gRPC Kotlin coroutine sources for every variant"
        }
        // The callback's parameter type is the extension's last type argument, which a star
        // projection would turn into Nothing; every variant is a Variant.
        @Suppress("UNCHECKED_CAST")
        val components = project.extensions.getByType(AndroidComponentsExtension::class.java)
            as AndroidComponentsExtension<*, *, Variant>
        components.onVariants(components.selector().all()) { variant ->
            val name = "generate${variant.name.replaceFirstChar { it.uppercaseChar() }}ScarabuffSources"
            val task: TaskProvider<GenerateScarabuffSourcesTask> =
                project.tasks.register(name, GenerateScarabuffSourcesTask::class.java) { configureTask(it) }
            variant.sources.java?.addGeneratedSourceDirectory(task, GenerateScarabuffSourcesTask::outputDir)
            all.configure { it.dependsOn(task) }
        }
    }
}
