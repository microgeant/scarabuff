package com.scarabuff.core

import com.scarabuff.core.codegen.GrpcCoroutineGenerator
import com.scarabuff.core.fbs.FbsRpcParser
import java.io.File

data class ScarabuffResult(
    val javaOutputDir: File,
    val kotlinOutputDir: File,
    val generatedKotlinFiles: List<File>,
)

/**
 * Ties the pieces together: run `flatc` to generate the base Java+gRPC classes, then parse the
 * same `.fbs` files' `rpc_service` blocks and emit a Kotlin coroutine wrapper per service.
 *
 * Shared by both the Gradle plugin (in-process call from a task action) and the CLI, so the two
 * front ends can't drift.
 */
object Orchestrator {

    /**
     * @param schemaDir directory containing `.fbs` files (non-recursive)
     * @param outputDir generated sources land in `outputDir/java` and `outputDir/kotlin`
     * @param flatc path to (or name on `$PATH` of) the `flatc` executable
     * @param kotlinPackageSuffix appended to each schema's `namespace` to form the package the
     *   generated `<Service>GrpcKt.kt` is written into. Empty (default) colocates it with the
     *   flatc-generated Java classes it wraps; pass e.g. `.coroutines` to put it in a subpackage.
     */
    fun generate(
        schemaDir: File,
        outputDir: File,
        flatc: String = "flatc",
        kotlinPackageSuffix: String = "",
    ): ScarabuffResult {
        val fbsFiles = schemaDir.listFiles { f -> f.isFile && f.extension == "fbs" }
            ?.sortedBy { it.name }
            .orEmpty()
        require(fbsFiles.isNotEmpty()) { "No .fbs files found in $schemaDir" }

        val javaOutputDir = outputDir.resolve("java")
        val kotlinOutputDir = outputDir.resolve("kotlin")
        javaOutputDir.mkdirs()
        kotlinOutputDir.mkdirs()

        runFlatc(flatc, fbsFiles, javaOutputDir)

        val generatedFiles = mutableListOf<File>()
        for (fbsFile in fbsFiles) {
            val schema = FbsRpcParser.parse(fbsFile.readText())
            val kotlinPackage = schema.namespace + kotlinPackageSuffix
            for (service in schema.services) {
                val fileSpec = GrpcCoroutineGenerator.generate(
                    service = service,
                    javaPackage = schema.namespace,
                    kotlinPackage = kotlinPackage,
                )
                fileSpec.writeTo(kotlinOutputDir)
                generatedFiles += kotlinOutputDir
                    .resolve(fileSpec.packageName.replace('.', File.separatorChar))
                    .resolve("${fileSpec.name}.kt")
            }
        }

        return ScarabuffResult(javaOutputDir, kotlinOutputDir, generatedFiles)
    }

    private fun runFlatc(flatc: String, fbsFiles: List<File>, outputDir: File) {
        val command = mutableListOf(flatc, "-o", outputDir.absolutePath, "--java", "--grpc")
        command += fbsFiles.map { it.absolutePath }

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "flatc failed (exit $exitCode) running: ${command.joinToString(" ")}\n$output"
        }
    }
}
