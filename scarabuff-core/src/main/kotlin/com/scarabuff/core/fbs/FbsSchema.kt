package com.scarabuff.core.fbs

/** How a single RPC method streams data, mirroring FlatBuffers' `rpc_service` `streaming` attribute. */
enum class StreamingKind(val attributeValue: String?) {
    UNARY(null),
    SERVER("server"),
    CLIENT("client"),
    BIDI("bidi");

    companion object {
        fun fromAttribute(value: String?): StreamingKind =
            entries.firstOrNull { it.attributeValue == value }
                ?: throw IllegalArgumentException("Unknown streaming attribute: \"$value\"")
    }
}

data class RpcMethod(
    val name: String,
    val requestType: String,
    val responseType: String,
    val streaming: StreamingKind,
)

data class RpcServiceDef(
    val name: String,
    val methods: List<RpcMethod>,
)

/**
 * The subset of a parsed `.fbs` file scarabuff needs: its namespace (used to resolve the
 * Java/Kotlin package flatc will emit types into) and any `rpc_service` definitions found in it.
 */
data class FbsFile(
    val namespace: String,
    val services: List<RpcServiceDef>,
)
