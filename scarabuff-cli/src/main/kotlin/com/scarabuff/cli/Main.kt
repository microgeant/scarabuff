package com.scarabuff.cli

import com.scarabuff.core.FlatcInstaller
import com.scarabuff.core.Orchestrator
import java.io.File
import kotlin.system.exitProcess

private val USAGE = """
Usage: scarabuff --schema-dir <dir> --out-dir <dir> [--flatc <path> | --flatc-version <version>] [--kotlin-package-suffix <suffix>] [--builders true|false]

  --schema-dir              Directory containing .fbs files (non-recursive). Required.
  --out-dir                 Generated Kotlin sources land here. Required.
  --flatc                   Path to (or name on PATH of) the flatc executable. Default: ${'$'}FLATC env var, else "flatc".
  --flatc-version           Download, verify and cache the official flatc for this FlatBuffers version
                            (in ~/.cache/scarabuff/flatc) instead. Known: ${FlatcInstaller.RELEASES.keys.joinToString()}.
  --kotlin-package-suffix   Appended to each schema's namespace for the generated Kotlin package. Default: "" (same package).
  --builders                Generate <Table>Builders.kt (build/buildBytes/fromBytes) for every table. Default: true.
"""

fun main(args: Array<String>) {
    val options = parseArgs(args)

    val schemaDir = options["--schema-dir"]?.let { File(it) }
    val outDir = options["--out-dir"]?.let { File(it) }
    if (schemaDir == null || outDir == null) {
        System.err.println(USAGE.trim())
        exitProcess(1)
    }

    val kotlinPackageSuffix = options["--kotlin-package-suffix"] ?: ""
    val generateBuilders = when (options["--builders"]) {
        null, "true" -> true
        "false" -> false
        else -> {
            System.err.println(USAGE.trim())
            exitProcess(1)
        }
    }

    try {
        val flatc = options["--flatc"]
            ?: options["--flatc-version"]?.let { version ->
                val cacheDir = File(System.getProperty("user.home"), ".cache/scarabuff/flatc")
                FlatcInstaller.install(version, cacheDir).absolutePath
            }
            ?: System.getenv("FLATC")
            ?: "flatc"
        val result = Orchestrator.generate(
            schemaDir = schemaDir,
            outputDir = outDir,
            flatc = flatc,
            kotlinPackageSuffix = kotlinPackageSuffix,
            generateBuilders = generateBuilders,
        )
        println("Generated ${result.generatedKotlinFiles.size} Kotlin file(s) in ${result.outputDir}")
        for ((table, reason) in result.tablesWithoutBuilders) {
            println("No build() for $table yet: it has a $reason (fromBytes is still generated)")
        }
    } catch (e: Exception) {
        System.err.println("scarabuff: ${e.message}")
        exitProcess(1)
    }
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val result = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i]
        val value = args.getOrNull(i + 1)
        if (!key.startsWith("--") || value == null) {
            System.err.println(USAGE.trim())
            exitProcess(1)
        }
        result[key] = value
        i += 2
    }
    return result
}
