package com.scarabuff.core.fbs

/** How a single RPC method streams data, mirroring FlatBuffers' `rpc_service` `streaming` attribute. */
enum class StreamingKind(val attributeValue: String?) {
    UNARY(null),
    SERVER("server"),
    CLIENT("client"),
    BIDI("bidi");

    companion object {
        /** `streaming: "none"` is flatc's explicit spelling of unary. */
        fun fromAttribute(value: String?): StreamingKind =
            entries.firstOrNull { it.attributeValue == (value.takeUnless { it == "none" }) }
                ?: throw IllegalArgumentException("Unknown streaming attribute: \"$value\"")
    }
}

/**
 * A table referenced by an RPC. [packageName] is the table's FlatBuffers namespace, which is also
 * the package flatc's `--kotlin` output puts its class in. Empty for a schema with no namespace.
 */
data class FbsTypeName(
    val packageName: String,
    val simpleName: String,
) {
    companion object {
        /** Splits a fully qualified FlatBuffers name like `com.example.Ping`. */
        fun fromQualified(qualifiedName: String): FbsTypeName = FbsTypeName(
            packageName = qualifiedName.substringBeforeLast('.', missingDelimiterValue = ""),
            simpleName = qualifiedName.substringAfterLast('.'),
        )
    }
}

data class RpcMethod(
    val name: String,
    val requestType: FbsTypeName,
    val responseType: FbsTypeName,
    val streaming: StreamingKind,
)

data class RpcServiceDef(
    /** The service's FlatBuffers namespace; empty for a schema with no namespace. */
    val namespace: String,
    val name: String,
    val methods: List<RpcMethod>,
) {
    /** The gRPC service name on the wire, e.g. `com.example.EchoService`. */
    val qualifiedName: String get() = if (namespace.isEmpty()) name else "$namespace.$name"
}

/** A FlatBuffers scalar, with the Kotlin types flatc's `--kotlin` output uses for it. */
enum class ScalarType(val kotlinType: String, val arrayType: String, val byteSize: Int) {
    BOOL("Boolean", "BooleanArray", 1),
    BYTE("Byte", "ByteArray", 1),
    UBYTE("UByte", "UByteArray", 1),
    SHORT("Short", "ShortArray", 2),
    USHORT("UShort", "UShortArray", 2),
    INT("Int", "IntArray", 4),
    UINT("UInt", "UIntArray", 4),
    LONG("Long", "LongArray", 8),
    ULONG("ULong", "ULongArray", 8),
    FLOAT("Float", "FloatArray", 4),
    DOUBLE("Double", "DoubleArray", 8),
}

/** What a table field holds, limited to the kinds scarabuff can generate builders for. */
sealed interface FieldKind {
    /** A scalar, or an enum stored as its underlying scalar ([enumType] is then set). */
    data class Scalar(val type: ScalarType, val enumType: FbsTypeName? = null) : FieldKind
    data object Str : FieldKind
    data class Table(val type: FbsTypeName) : FieldKind
    data class ScalarVector(val element: Scalar) : FieldKind
    data object StrVector : FieldKind
    data class TableVector(val element: FbsTypeName) : FieldKind
    data class Struct(val type: FbsTypeName) : FieldKind
    data class StructVector(val element: FbsTypeName) : FieldKind
    /**
     * A union of tables, [type] being the union itself (flatc's class of type constants). Stored
     * as two fields: a hidden `<name>_type` tag and the member table.
     */
    data class Union(val type: FbsTypeName, val members: List<UnionMember>) : FieldKind
}

/** One table in a union: [name] is flatc's constant for it, [value] the tag stored for it. */
data class UnionMember(val name: String, val value: Int, val table: FbsTypeName)

data class FieldDef(
    /** The name as written in the schema, e.g. `created_at`. */
    val name: String,
    /** The field's slot in the table's vtable (its `id`). */
    val id: Int,
    val kind: FieldKind,
    val required: Boolean,
    /** A scalar declared `= null`: absent unless set, rather than defaulted. */
    val optionalScalar: Boolean,
    /** The schema default as a Kotlin literal (`42`, `1.5f`, `9u`, ...); null for non-scalars. */
    val defaultValue: String?,
    /** For an enum field whose default matches one of its values, that value's name. */
    val defaultEnumValue: String? = null,
)

/**
 * A table from the compiled schemas. [unsupportedReason] is set when it has a field kind the
 * builder generator doesn't handle yet; such tables still get `fromBytes`, but no builders.
 */
data class TableDef(
    val type: FbsTypeName,
    val fields: List<FieldDef>,
    /** Set when this table is the schema's `root_type` and the schema declares a `file_identifier`. */
    val fileIdentifier: String?,
    val unsupportedReason: String?,
)

/**
 * A struct from the compiled schemas: fixed-size and inline, so every field is always present.
 * Its fields are scalars (or enums), or other structs.
 */
data class StructDef(
    val type: FbsTypeName,
    val fields: List<StructFieldDef>,
    /** Its encoded size in bytes, padding included, as flatc lays it out. */
    val byteSize: Int,
)

data class StructFieldDef(
    /** The name as written in the schema. */
    val name: String,
    /** A scalar or enum ([FieldKind.Scalar]) or a nested struct ([FieldKind.Struct]). */
    val kind: FieldKind,
)
