package com.scarabuff.core.fbs

import com.google.flatbuffers.reflection.BaseType
import com.google.flatbuffers.reflection.Field
import com.google.flatbuffers.reflection.KeyValue
import com.google.flatbuffers.reflection.Object
import com.google.flatbuffers.reflection.RPCCall
import com.google.flatbuffers.reflection.Schema
import com.google.flatbuffers.reflection.Service
import com.google.flatbuffers.reflection.Type
import java.nio.ByteBuffer

/**
 * Reads `rpc_service` definitions from a binary schema (`.bfbs`), the output of
 * `flatc --binary --schema`.
 *
 * flatc has already parsed and validated the schema by this point, so includes, comments, and
 * namespace resolution are all handled by flatc itself, and every type name is fully qualified.
 * A `.bfbs` also contains everything from the files it includes, so callers combining several
 * should de-duplicate services by [RpcServiceDef.qualifiedName].
 */
object BfbsReader {

    fun readServices(bfbs: ByteBuffer): List<RpcServiceDef> {
        val schema = Schema.getRootAsSchema(bfbs)
        return (0 until schema.servicesLength()).map { readService(schema.services(it)) }
    }

    /**
     * Reads the tables (not structs) declared in [declarationFiles], which are paths as flatc
     * writes them with `--bfbs-filenames <schema dir>`, e.g. `//echo.fbs`. Tables from other files
     * (includes outside the schema directory) are skipped: flatc generated no classes for them.
     */
    fun readTables(bfbs: ByteBuffer, declarationFiles: Set<String>): List<TableDef> {
        val schema = Schema.getRootAsSchema(bfbs)
        val root = schema.rootTable()?.name()
        val fileIdentifier = schema.fileIdent()?.takeIf { it.isNotEmpty() }
        return (0 until schema.objectsLength())
            .map { schema.objects(it) }
            .filter { !it.isStruct && it.declarationFile() in declarationFiles }
            .map { table -> readTable(schema, table, fileIdentifier.takeIf { table.name() == root }) }
    }

    /** Reads the structs declared in [declarationFiles]; see [readTables]. */
    fun readStructs(bfbs: ByteBuffer, declarationFiles: Set<String>): List<StructDef> {
        val schema = Schema.getRootAsSchema(bfbs)
        return (0 until schema.objectsLength())
            .map { schema.objects(it) }
            .filter { it.isStruct && it.declarationFile() in declarationFiles }
            .map { struct ->
                StructDef(
                    type = FbsTypeName.fromQualified(struct.name()),
                    fields = (0 until struct.fieldsLength())
                        .map { struct.fields(it) }
                        .sortedBy { it.id() } // flatc's create<Struct> takes fields in declaration order
                        .map { field -> StructFieldDef(field.name(), structFieldKind(schema, struct, field)) },
                )
            }
    }

    /** A struct field is a scalar, an enum or another struct; flatc rejects fixed-size arrays for Kotlin. */
    private fun structFieldKind(schema: Schema, struct: Object, field: Field): FieldKind =
        when (val kind = fieldKind(schema, field.type())) {
            is Kind.Supported -> kind.kind.takeIf { it is FieldKind.Scalar || it is FieldKind.Struct }
            is Kind.Unsupported -> null
        } ?: error("Unexpected field `${field.name()}` in struct ${struct.name()}")

    private fun readTable(schema: Schema, table: Object, fileIdentifier: String?): TableDef {
        val fields = (0 until table.fieldsLength())
            .map { table.fields(it) }
            .filter { !it.deprecated() }
            // A union field `x` (or vector of unions) comes with a hidden `x_type` field; the union
            // itself is what's reported.
            .filter { it.type().baseType() != BaseType.UType && it.type().element() != BaseType.UType }
            .sortedBy { it.id() } // the .bfbs sorts fields by name; builders follow declaration order
        val unsupported = mutableListOf<String>()
        val defs = fields.mapNotNull { field ->
            when (val kind = fieldKind(schema, field.type())) {
                is Kind.Unsupported -> null.also { unsupported += "${kind.what} field `${field.name()}`" }
                is Kind.Supported -> fieldDef(schema, field, kind.kind)
            }
        }
        if (fields.any { it.name() == "builder" }) unsupported += "a field named `builder`"
        return TableDef(
            type = FbsTypeName.fromQualified(table.name()),
            fields = defs,
            fileIdentifier = fileIdentifier,
            unsupportedReason = unsupported.takeIf { it.isNotEmpty() }?.joinToString(", "),
        )
    }

    private sealed interface Kind {
        data class Supported(val kind: FieldKind) : Kind
        /** [what] names the field kind the builder generator can't handle yet. */
        data class Unsupported(val what: String) : Kind
    }

    private fun fieldKind(schema: Schema, type: Type): Kind = when (type.baseType()) {
        BaseType.String -> Kind.Supported(FieldKind.Str)
        BaseType.Obj ->
            if (schema.objects(type.index()).isStruct) Kind.Supported(FieldKind.Struct(objectName(schema, type)))
            else Kind.Supported(FieldKind.Table(objectName(schema, type)))
        BaseType.Union -> Kind.Unsupported("union")
        BaseType.Array -> Kind.Unsupported("fixed-size array")
        BaseType.Vector64 -> Kind.Unsupported("64-bit vector")
        BaseType.Vector -> when (val element = type.element()) {
            BaseType.String -> Kind.Supported(FieldKind.StrVector)
            BaseType.Obj ->
                if (schema.objects(type.index()).isStruct) Kind.Supported(FieldKind.StructVector(objectName(schema, type)))
                else Kind.Supported(FieldKind.TableVector(objectName(schema, type)))
            BaseType.Union -> Kind.Unsupported("vector-of-unions")
            else -> scalar(schema, element, type.index())?.let { Kind.Supported(FieldKind.ScalarVector(it)) }
                ?: Kind.Unsupported("vector")
        }
        else -> scalar(schema, type.baseType(), type.index())?.let { Kind.Supported(it) }
            ?: Kind.Unsupported("`${BaseType.name(type.baseType().toInt())}`")
    }

    private fun objectName(schema: Schema, type: Type) = FbsTypeName.fromQualified(schema.objects(type.index()).name())

    private fun scalar(schema: Schema, baseType: Byte, enumIndex: Int): FieldKind.Scalar? {
        val scalarType = when (baseType) {
            BaseType.Bool -> ScalarType.BOOL
            BaseType.Byte -> ScalarType.BYTE
            BaseType.UByte -> ScalarType.UBYTE
            BaseType.Short -> ScalarType.SHORT
            BaseType.UShort -> ScalarType.USHORT
            BaseType.Int -> ScalarType.INT
            BaseType.UInt -> ScalarType.UINT
            BaseType.Long -> ScalarType.LONG
            BaseType.ULong -> ScalarType.ULONG
            BaseType.Float -> ScalarType.FLOAT
            BaseType.Double -> ScalarType.DOUBLE
            else -> return null
        }
        val enumType = if (enumIndex >= 0) FbsTypeName.fromQualified(schema.enums(enumIndex).name()) else null
        return FieldKind.Scalar(scalarType, enumType)
    }

    private fun fieldDef(schema: Schema, field: Field, kind: FieldKind): FieldDef {
        val scalar = kind as? FieldKind.Scalar
        val optional = scalar != null && field.optional()
        val enumValue = if (scalar?.enumType != null && !optional) {
            val enum = schema.enums(field.type().index())
            (0 until enum.valuesLength()).map { enum.values(it) }
                .firstOrNull { it.value() == field.defaultInteger() }?.name()
        } else null
        return FieldDef(
            name = field.name(),
            id = field.id(),
            kind = kind,
            required = field.required(),
            optionalScalar = optional,
            defaultValue = if (scalar != null && !optional) kotlinLiteral(scalar.type, field) else null,
            defaultEnumValue = enumValue,
        )
    }

    private fun kotlinLiteral(type: ScalarType, field: Field): String {
        val i = field.defaultInteger()
        val d = field.defaultReal()
        return when (type) {
            ScalarType.BOOL -> (i != 0L).toString()
            ScalarType.BYTE, ScalarType.SHORT, ScalarType.INT -> "$i"
            ScalarType.LONG -> if (i == Long.MIN_VALUE) "Long.MIN_VALUE" else "${i}L"
            ScalarType.UBYTE, ScalarType.USHORT, ScalarType.UINT -> "${i}u"
            ScalarType.ULONG -> "${java.lang.Long.toUnsignedString(i)}uL"
            ScalarType.FLOAT -> floating(d, "Float") { "${d.toFloat()}f" }
            ScalarType.DOUBLE -> floating(d, "Double") { "$d" }
        }
    }

    private fun floating(value: Double, type: String, literal: () -> String): String = when {
        value.isNaN() -> "$type.NaN"
        value == Double.POSITIVE_INFINITY -> "$type.POSITIVE_INFINITY"
        value == Double.NEGATIVE_INFINITY -> "$type.NEGATIVE_INFINITY"
        else -> literal()
    }

    private fun readService(service: Service): RpcServiceDef {
        val qualified = FbsTypeName.fromQualified(service.name())
        return RpcServiceDef(
            namespace = qualified.packageName,
            name = qualified.simpleName,
            methods = (0 until service.callsLength()).map { readCall(service.calls(it)) },
        )
    }

    private fun readCall(call: RPCCall): RpcMethod {
        val attributes = (0 until call.attributesLength()).map { call.attributes(it) }
        return RpcMethod(
            name = call.name(),
            requestType = FbsTypeName.fromQualified(call.request().name()),
            responseType = FbsTypeName.fromQualified(call.response().name()),
            streaming = StreamingKind.fromAttribute(attributes.valueOf("streaming")),
        )
    }

    private fun List<KeyValue>.valueOf(key: String): String? = firstOrNull { it.key() == key }?.value()
}
