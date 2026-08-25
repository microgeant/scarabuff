package com.scarabuff.core.fbs

/**
 * Extracts the pieces of a `.fbs` schema scarabuff needs to generate coroutine wrappers:
 * the file's `namespace` declaration and every `rpc_service` block.
 *
 * This is a lightweight text parser, not a full FlatBuffers IDL grammar implementation. It
 * covers the `rpc_service` syntax flatc itself accepts (see the FlatBuffers grammar reference),
 * which is all scarabuff needs: table/struct/enum/union definitions and everything else in the
 * schema are left to flatc.
 */
object FbsRpcParser {

    private val namespaceRegex = Regex("""namespace\s+([\w.]+)\s*;""")
    private val rpcServiceRegex = Regex("""rpc_service\s+(\w+)\s*\{([^}]*)}""")
    private val rpcMethodRegex = Regex(
        """(\w+)\s*\(\s*(\w+)\s*\)\s*:\s*(\w+)\s*(?:\(\s*streaming\s*:\s*"(\w+)"\s*\))?\s*;"""
    )

    fun parse(schemaText: String): FbsFile {
        val stripped = stripComments(schemaText)

        val namespace = namespaceRegex.find(stripped)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Schema has no `namespace` declaration; scarabuff requires one")

        val services = rpcServiceRegex.findAll(stripped).map { serviceMatch ->
            val serviceName = serviceMatch.groupValues[1]
            val body = serviceMatch.groupValues[2]
            val methods = rpcMethodRegex.findAll(body).map { methodMatch ->
                val (methodName, requestType, responseType, streamingAttr) = methodMatch.destructured
                RpcMethod(
                    name = methodName,
                    requestType = requestType,
                    responseType = responseType,
                    streaming = StreamingKind.fromAttribute(streamingAttr.ifEmpty { null }),
                )
            }.toList()
            if (methods.isEmpty()) {
                throw IllegalArgumentException("rpc_service $serviceName has no parseable RPC methods")
            }
            RpcServiceDef(name = serviceName, methods = methods)
        }.toList()

        return FbsFile(namespace = namespace, services = services)
    }

    private fun stripComments(text: String): String {
        val noBlockComments = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL).replace(text, " ")
        return noBlockComments.lineSequence()
            .joinToString("\n") { line ->
                val idx = line.indexOf("//")
                if (idx >= 0) line.substring(0, idx) else line
            }
    }
}
