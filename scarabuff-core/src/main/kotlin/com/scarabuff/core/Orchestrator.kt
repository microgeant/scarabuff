package com.scarabuff.core

import com.scarabuff.core.codegen.GrpcCoroutineGenerator
import com.scarabuff.core.fbs.FbsRpcParser
import java.io.File

data class ScarabuffResult(
    val outputDir: File,
    val generatedKotlinFiles: List<File>,
)

/**
 * Ties the pieces together: run `flatc --kotlin` to generate the FlatBuffers table classes, then
 * parse the same `.fbs` files' `rpc_service` blocks and emit a Kotlin coroutine wrapper per
 * service. flatc's `--grpc` generator has no Kotlin backend, so the gRPC `ServiceDescriptor`/
 * `MethodDescriptor` glue is built directly by [GrpcCoroutineGenerator] instead of delegated to a
 * flatc-generated Java class — the whole output is Kotlin, no `.java` files involved.
 *
 * Shared by both the Gradle plugin (in-process call from a task action) and the CLI, so the two
 * front ends can't drift.
 */
object Orchestrator {

    /**
     * @param schemaDir directory containing `.fbs` files (non-recursive)
     * @param outputDir generated Kotlin sources (both flatc's table classes and the
     *   `<Service>GrpcKt` wrappers) land here
     * @param flatc path to (or name on `$PATH` of) the `flatc` executable
     * @param kotlinPackageSuffix appended to each schema's `namespace` to form the package the
     *   generated `<Service>GrpcKt.kt` is written into. Empty (default) colocates it with the
     *   flatc-generated table classes it wraps; pass e.g. `.coroutines` to put it in a subpackage.
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

        outputDir.mkdirs()

        runFlatc(flatc, fbsFiles, outputDir)

        val generatedFiles = mutableListOf<File>()
        for (fbsFile in fbsFiles) {
            val schema = FbsRpcParser.parse(fbsFile.readText())
            val kotlinPackage = schema.namespace + kotlinPackageSuffix
            for (service in schema.services) {
                val fileSpec = GrpcCoroutineGenerator.generate(
                    service = service,
                    dataPackage = schema.namespace,
                    kotlinPackage = kotlinPackage,
                )
                fileSpec.writeTo(outputDir)
                generatedFiles += outputDir
                    .resolve(fileSpec.packageName.replace('.', File.separatorChar))
                    .resolve("${fileSpec.name}.kt")
            }
        }

        return ScarabuffResult(outputDir, generatedFiles)
    }

    private fun runFlatc(flatc: String, fbsFiles: List<File>, outputDir: File) {
        val command = mutableListOf(flatc, "-o", outputDir.absolutePath, "--kotlin")
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
