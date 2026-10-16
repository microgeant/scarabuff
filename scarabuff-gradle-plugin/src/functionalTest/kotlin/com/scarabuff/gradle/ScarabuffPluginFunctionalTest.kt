package com.scarabuff.gradle

import com.google.common.truth.Truth.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File

/**
 * Builds a copy of example/ against the published plugin (see the `functionalTest` task) and runs
 * its round-trip tests, together with builders-fixture/ (a schema using every field kind the
 * builder generator supports, and tests that round-trip each one). The fixture declares none of
 * the runtime libraries and has no flatc: both must come from the plugin.
 */
class ScarabuffPluginFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private val repo = System.getProperty("scarabuff.repo")
    private val pluginVersion = System.getProperty("scarabuff.version")
    private val example = File(System.getProperty("scarabuff.example"))

    private fun fixture(kotlinVersion: String, extraBuildScript: String = "") {
        example.resolve("src").copyRecursively(projectDir.resolve("src"))
        File(javaClass.getResource("/builders-fixture/src")!!.toURI()).copyRecursively(projectDir.resolve("src"))
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    maven(url = "${repo.replace('\\', '/')}")
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositories { mavenCentral() }
            }
            rootProject.name = "fixture"
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm") version "$kotlinVersion"
                id("io.github.microgeant.scarabuff") version "$pluginVersion"
            }

            kotlin { jvmToolchain(17) }

            dependencies {
                testImplementation("io.grpc:grpc-inprocess:1.70.0")
                // Real transports, for example/'s StreamingBuilderReuseTest.
                testImplementation("io.grpc:grpc-netty:1.70.0")
                testImplementation("io.grpc:grpc-okhttp:1.70.0")
                testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
                testImplementation("com.google.truth:truth:1.4.0")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
            }

            tasks.test { useJUnitPlatform() }
            $extraBuildScript
            """.trimIndent()
        )
    }

    /** Number of passing tests in one of the fixture's JUnit reports. */
    private fun testCount(testClass: String): Int {
        val report = projectDir.resolve("build/test-results/test/TEST-$testClass.xml").readText()
        val tests = Regex("""tests="(\d+)"""").find(report)!!.groupValues[1].toInt()
        val failed = Regex("""(?:failures|errors)="(\d+)"""").findAll(report).sumOf { it.groupValues[1].toInt() }
        return tests - failed
    }

    /** No FLATC, so the plugin's own download path is what's exercised. */
    private fun runner(gradleVersion: String, vararg args: String): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDir)
        .withGradleVersion(gradleVersion)
        .withEnvironment(System.getenv() - "FLATC")
        .withArguments(*args, "--stacktrace", "--warning-mode", "all")
        .forwardOutput()

    @ParameterizedTest(name = "Gradle {0}, Kotlin {1}")
    @CsvSource(
        "8.7, 1.9.0",
        "8.14.6, 2.1.21",
        "9.1.0, 2.2.10",
        "9.8.1, 2.4.21",
    )
    fun `example builds and its round-trip tests pass`(gradleVersion: String, kotlinVersion: String) {
        fixture(kotlinVersion)

        val first = runner(gradleVersion, "test", "--configuration-cache").build()
        assertThat(first.task(":generateScarabuffSources")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(first.task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        // Make sure the fixture's own tests actually ran, not just that the task succeeded.
        assertThat(testCount("com.scarabuff.example.EchoRoundTripTest")).isEqualTo(5)
        assertThat(testCount("com.scarabuff.example.StreamingBuilderReuseTest")).isEqualTo(3)
        assertThat(testCount("com.scarabuff.example.RequiredFieldsTest")).isEqualTo(4)
        assertThat(testCount("com.scarabuff.example.MarshallerTest")).isEqualTo(3)
        assertThat(testCount("com.probe.builders.BuildersTest")).isEqualTo(22)

        val second = runner(gradleVersion, "test", "--configuration-cache").build()
        assertThat(second.output).contains("Configuration cache entry reused")
        assertThat(second.task(":generateScarabuffSources")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    }

    /**
     * The other FlatBuffers version scarabuff supports: selecting it must switch both the runtime
     * jar and flatc, and everything must still pass.
     */
    @Test
    fun `an older FlatBuffers version works when flatbuffersVersion selects it`() {
        fixture(
            "2.4.21",
            """
            scarabuff { flatbuffersVersion.set("24.3.25") }
            tasks.register("printFlatbuffersJava") {
                val version = configurations.named("compileClasspath").map { classpath ->
                    classpath.incoming.resolutionResult.allComponents
                        .mapNotNull { it.moduleVersion }.single { it.name == "flatbuffers-java" }.version
                }
                doLast { println("flatbuffers-java " + version.get()) }
            }
            """.trimIndent(),
        )

        val result = runner("9.8.1", "test", "printFlatbuffersJava").build()

        assertThat(result.output).contains("flatbuffers-java 24.3.25")
        // flatc's output checks the runtime matches the compiler that wrote it.
        val generated = projectDir.resolve("build").walk().first { it.name == "Strict.kt" }.readText()
        assertThat(generated).contains("Constants.FLATBUFFERS_24_3_25()")
        assertThat(testCount("com.scarabuff.example.EchoRoundTripTest")).isEqualTo(5)
        assertThat(testCount("com.scarabuff.example.RequiredFieldsTest")).isEqualTo(4)
        assertThat(testCount("com.probe.builders.BuildersTest")).isEqualTo(22)
    }

    @Test
    fun `without the runtime dependencies the build explains what's missing`() {
        fixture("2.4.21", "scarabuff { addRuntimeDependencies.set(false) }")

        val result = runner("9.8.1", "generateScarabuffSources").buildAndFail()

        assertThat(result.output).contains("flatbuffers-java isn't on the compile classpath")
    }
}
