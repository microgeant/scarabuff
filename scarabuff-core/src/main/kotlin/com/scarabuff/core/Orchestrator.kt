package com.scarabuff.core

import com.scarabuff.core.codegen.GrpcCoroutineGenerator
import com.scarabuff.core.codegen.TableBuilderGenerator
import com.scarabuff.core.fbs.BfbsReader
import com.scarabuff.core.fbs.RpcServiceDef
import com.scarabuff.core.fbs.TableDef
import com.squareup.kotlinpoet.FileSpec
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

data class ScarabuffResult(
    val outputDir: File,
    val generatedKotlinFiles: List<File>,
    /** Tables that got `fromBytes` but no builders, mapped to why (e.g. a union field). */
    val tablesWithoutBuilders: Map<String, String> = emptyMap(),
)

/**
 * Ties the pieces together: run `flatc --kotlin` to generate the FlatBuffers table classes, then
 * read the same schemas from flatc's binary schema output (`--binary --schema`) and emit a Kotlin
 * coroutine wrapper per `rpc_service` and builder functions per table.
 *
 * flatc's `--grpc` generator has no Kotlin backend, so the gRPC `ServiceDescriptor`/
 * `MethodDescriptor` glue is built directly by [GrpcCoroutineGenerator] instead of delegated to a
 * flatc-generated Java class — the whole output is Kotlin, no `.java` files involved.
 *
 * Shared by both the Gradle plugin (in-process call from a task action) and the CLI, so the two
 * front ends can't drift.
 */
object Orchestrator {

    /**
     * @param schemaDir directory containing `.fbs` files (non-recursive)
     * @param outputDir generated Kotlin sources (flatc's table classes, the `<Service>GrpcKt`
     *   wrappers and the `<Table>Builders` files) land here
     * @param flatc path to (or name on `$PATH` of) the `flatc` executable
     * @param kotlinPackageSuffix appended to each service's `namespace` to form the package the
     *   generated `<Service>GrpcKt.kt` is written into. Empty (default) colocates it with the
     *   flatc-generated table classes it wraps; pass e.g. `.coroutines` to put it in a subpackage.
     * @param generateBuilders whether to emit `<Table>Builders.kt` files (see [TableBuilderGenerator])
     */
    fun generate(
        schemaDir: File,
        outputDir: File,
        flatc: String = "flatc",
        kotlinPackageSuffix: String = "",
        generateBuilders: Boolean = true,
    ): ScarabuffResult {
        val fbsFiles = schemaDir.listFiles { f -> f.isFile && f.extension == "fbs" }
            ?.sortedBy { it.name }
            .orEmpty()
        require(fbsFiles.isNotEmpty()) { "No .fbs files found in $schemaDir" }

        outputDir.mkdirs()

        runFlatc(flatc, fbsFiles, outputDir, "--kotlin")
        val schema = readSchema(flatc, schemaDir, fbsFiles)

        val generatedFiles = mutableListOf<File>()
        fun write(fileSpec: FileSpec) {
            fileSpec.writeTo(outputDir)
            generatedFiles += outputDir
                .resolve(fileSpec.packageName.replace('.', File.separatorChar))
                .resolve("${fileSpec.name}.kt")
        }

        for (service in schema.services) {
            write(
                GrpcCoroutineGenerator.generate(
                    service = service,
                    kotlinPackage = kotlinPackage(service.namespace, kotlinPackageSuffix),
                )
            )
        }
        val tablesWithoutBuilders = mutableMapOf<String, String>()
        if (generateBuilders) {
            for (table in schema.tables) {
                write(TableBuilderGenerator.generate(table))
                table.unsupportedReason?.let { tablesWithoutBuilders["${table.type.packageName}.${table.type.simpleName}"] = it }
            }
        }

        return ScarabuffResult(outputDir, generatedFiles, tablesWithoutBuilders)
    }

    private class Schema(val services: List<RpcServiceDef>, val tables: List<TableDef>)

    /**
     * Compiles every schema to a `.bfbs` in a scratch directory and reads its services and tables.
     * Each `.bfbs` also contains everything from the files it includes, hence the de-duplication.
     */
    private fun readSchema(flatc: String, schemaDir: File, fbsFiles: List<File>): Schema {
        val bfbsDir = Files.createTempDirectory("scarabuff-bfbs").toFile()
        try {
            // --bfbs-builtins: without it flatc leaves built-in attributes such as `streaming` out of
            // the .bfbs, and every RPC would read as unary.
            // --bfbs-filenames: records which file declared each table, as `//<path in schemaDir>`.
            runFlatc(
                flatc, fbsFiles, bfbsDir,
                "--binary", "--schema", "--bfbs-builtins", "--bfbs-filenames", schemaDir.absolutePath,
            )
            val compiled = fbsFiles.map { "//${it.name}" }.toSet()
            val bfbs = fbsFiles.map { ByteBuffer.wrap(bfbsDir.resolve("${it.nameWithoutExtension}.bfbs").readBytes()) }
            return Schema(
                services = bfbs.flatMap { BfbsReader.readServices(it) }.distinctBy { it.qualifiedName },
                tables = bfbs.flatMap { BfbsReader.readTables(it.duplicate(), compiled) }
                    .groupBy { it.type }
                    // A table is only the root_type in its own file's .bfbs, so keep the copy that
                    // knows its file_identifier, if any.
                    .map { (_, copies) -> copies.firstOrNull { it.fileIdentifier != null } ?: copies.first() },
            )
        } finally {
            bfbsDir.deleteRecursively()
        }
    }

    private fun kotlinPackage(namespace: String, suffix: String): String =
        if (namespace.isEmpty()) suffix.removePrefix(".") else namespace + suffix

    private fun runFlatc(flatc: String, fbsFiles: List<File>, outputDir: File, vararg flags: String) {
        val command = mutableListOf(flatc, "-o", outputDir.absolutePath, *flags)
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
