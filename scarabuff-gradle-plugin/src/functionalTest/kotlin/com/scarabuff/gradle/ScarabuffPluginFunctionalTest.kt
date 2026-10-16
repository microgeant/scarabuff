package com.scarabuff.gradle

import com.google.common.truth.Truth.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assumptions.assumeTrue
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

    /**
     * The same sources as [fixture], in an Android module: an app, or a library. AGP 9 compiles
     * Kotlin itself; before that, [kotlinVersion] applies the Kotlin Android plugin. The tests run
     * as local unit tests, on the JVM, against the debug variant.
     */
    private fun androidFixture(agpVersion: String, app: Boolean, kotlinVersion: String? = null) {
        example.resolve("src").copyRecursively(projectDir.resolve("src"))
        File(javaClass.getResource("/builders-fixture/src")!!.toURI()).copyRecursively(projectDir.resolve("src"))
        projectDir.resolve("src/main/AndroidManifest.xml")
            .writeText("""<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application/></manifest>""")
        projectDir.resolve("local.properties").writeText("sdk.dir=${androidSdk().absolutePath.replace('\\', '/')}\n")
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    maven(url = "${repo.replace('\\', '/')}")
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositories { google(); mavenCentral() }
            }
            rootProject.name = "fixture"
            """.trimIndent()
        )
        val androidPlugin = if (app) "com.android.application" else "com.android.library"
        val kotlinPlugin = kotlinVersion?.let { """kotlin("android") version "$it"""" } ?: ""
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("$androidPlugin") version "$agpVersion"
                $kotlinPlugin
                id("io.github.microgeant.scarabuff") version "$pluginVersion"
            }

            android {
                namespace = "com.probe.android"
                compileSdk = 36
                defaultConfig {
                    minSdk = 24
                    ${if (app) "applicationId = \"com.probe.android\"" else ""}
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }
            ${if (kotlinVersion != null) "kotlin { jvmToolchain(17) }" else ""}

            dependencies {
                testImplementation("io.grpc:grpc-inprocess:1.70.0")
                testImplementation("io.grpc:grpc-netty:1.70.0")
                testImplementation("io.grpc:grpc-okhttp:1.70.0")
                testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
                testImplementation("com.google.truth:truth:1.4.0")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
            }

            tasks.withType<Test>().configureEach { useJUnitPlatform() }
            """.trimIndent()
        )
    }

    /**
     * The Android SDK: `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or Android Studio's default location.
     * Without one the Android tests are skipped locally, but fail on CI, where they must run.
     */
    private fun androidSdk(): File {
        val candidates = listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            System.getProperty("user.home") + "/Library/Android/sdk",
            System.getProperty("user.home") + "/Android/Sdk",
        ).map(::File)
        val sdk = candidates.firstOrNull { it.resolve("platforms").isDirectory }
        if (sdk == null && System.getenv("CI") != null) error("No Android SDK found; tried $candidates")
        assumeTrue(sdk != null, "No Android SDK found; tried $candidates")
        return sdk!!
    }

    /** Number of passing tests in one of the fixture's JUnit reports. */
    private fun testCount(testClass: String, task: String = "test"): Int {
        val report = projectDir.resolve("build/test-results/$task/TEST-$testClass.xml").readText()
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

    /**
     * The plugin in an Android module: an app on AGP 9 with its built-in Kotlin, like most new
     * apps, and a library on AGP 8 with the Kotlin Android plugin. Each variant gets generated
     * sources; the debug ones run every test the JVM fixture runs, and release must build too.
     */
    @ParameterizedTest(name = "AGP {0} {1}, Gradle {2}, Kotlin {3}")
    @CsvSource(
        "9.0.1, app, 9.1.0, built-in",
        "8.13.0, library, 8.14.6, 2.1.21",
    )
    fun `an Android module builds and its tests pass`(agpVersion: String, kind: String, gradleVersion: String, kotlin: String) {
        androidFixture(agpVersion, app = kind == "app", kotlinVersion = kotlin.takeUnless { it == "built-in" })

        val result = runner(gradleVersion, "testDebugUnitTest", "assembleRelease").build()

        assertThat(result.task(":generateDebugScarabuffSources")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":generateReleaseScarabuffSources")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":testDebugUnitTest")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val task = "testDebugUnitTest"
        assertThat(testCount("com.scarabuff.example.EchoRoundTripTest", task)).isEqualTo(5)
        assertThat(testCount("com.scarabuff.example.StreamingBuilderReuseTest", task)).isEqualTo(3)
        assertThat(testCount("com.scarabuff.example.RequiredFieldsTest", task)).isEqualTo(4)
        assertThat(testCount("com.scarabuff.example.MarshallerTest", task)).isEqualTo(3)
        assertThat(testCount("com.probe.builders.BuildersTest", task)).isEqualTo(22)

        // Nothing changed, so nothing is generated again.
        val second = runner(gradleVersion, "testDebugUnitTest").build()
        assertThat(second.task(":generateDebugScarabuffSources")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    }

    @Test
    fun `without the runtime dependencies the build explains what's missing`() {
        fixture("2.4.21", "scarabuff { addRuntimeDependencies.set(false) }")

        val result = runner("9.8.1", "generateScarabuffSources").buildAndFail()

        assertThat(result.output).contains("flatbuffers-java isn't on the compile classpath")
    }
}
