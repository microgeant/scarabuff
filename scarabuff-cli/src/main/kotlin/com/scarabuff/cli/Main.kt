package com.scarabuff.cli

import com.scarabuff.core.Orchestrator
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """
Usage: scarabuff --schema-dir <dir> --out-dir <dir> [--flatc <path>] [--kotlin-package-suffix <suffix>]

  --schema-dir              Directory containing .fbs files (non-recursive). Required.
  --out-dir                 Generated Java lands in <dir>/java, Kotlin in <dir>/kotlin. Required.
  --flatc                   Path to (or name on PATH of) the flatc executable. Default: ${'$'}FLATC env var, else "flatc".
  --kotlin-package-suffix   Appended to each schema's namespace for the generated Kotlin package. Default: "" (same package).
"""

fun main(args: Array<String>) {
    val options = parseArgs(args)

    val schemaDir = options["--schema-dir"]?.let { File(it) }
    val outDir = options["--out-dir"]?.let { File(it) }
    if (schemaDir == null || outDir == null) {
        System.err.println(USAGE.trim())
        exitProcess(1)
    }

    val flatc = options["--flatc"] ?: System.getenv("FLATC") ?: "flatc"
    val kotlinPackageSuffix = options["--kotlin-package-suffix"] ?: ""

    try {
        val result = Orchestrator.generate(
            schemaDir = schemaDir,
            outputDir = outDir,
            flatc = flatc,
            kotlinPackageSuffix = kotlinPackageSuffix,
        )
        println("Generated Java sources in ${result.javaOutputDir}")
        println("Generated ${result.generatedKotlinFiles.size} Kotlin coroutine wrapper(s) in ${result.kotlinOutputDir}")
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
