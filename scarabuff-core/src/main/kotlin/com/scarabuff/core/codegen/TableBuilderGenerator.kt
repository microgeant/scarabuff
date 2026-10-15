package com.scarabuff.core.codegen

import com.scarabuff.core.fbs.FbsTypeName
import com.scarabuff.core.fbs.FieldDef
import com.scarabuff.core.fbs.FieldKind
import com.scarabuff.core.fbs.ScalarType
import com.scarabuff.core.fbs.TableDef
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BYTE_ARRAY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName

/**
 * Generates `<Table>Builders.kt` for one table: extension functions on the flatc-generated class
 * that build a finished message from named arguments, and read one back from bytes. flatc's
 * Kotlin backend has no object API, so without these every message needs hand-written
 * `FlatBufferBuilder` calls.
 *
 * For a table like `table User { id:uint; name:string (required); }` this emits:
 * - `User.build(id = 1u, name = "Ada"): User` and `User.buildBytes(...): ByteArray`
 * - `User.fromBytes(bytes): User`
 * - `User.pack(builder, ...): Int`, which writes into an existing builder and returns the offset,
 *   and `user.copyInto(builder): Int`, which re-serializes an existing message. Builders of other
 *   tables use these to copy nested messages in.
 *
 * Required fields become non-null parameters without defaults; everything else defaults to the
 * schema's default (or null). All calls target flatc's own `start`/`add`/`end`/`create*Vector`
 * functions, so the wire format is exactly what flatc would produce.
 */
object TableBuilderGenerator {

    private val FLAT_BUFFER_BUILDER = ClassName("com.google.flatbuffers", "FlatBufferBuilder")
    private val BYTE_BUFFER = ClassName("java.nio", "ByteBuffer")
    private val EXPERIMENTAL_UNSIGNED_TYPES = ClassName("kotlin", "ExperimentalUnsignedTypes")
    private val OPT_IN = ClassName("kotlin", "OptIn")

    fun generate(table: TableDef): FileSpec {
        val tableClass = table.type.toClassName()
        val file = FileSpec.builder(table.type.packageName, "${table.type.simpleName}Builders")
            .addFunction(fromBytes(tableClass))
        if (table.unsupportedReason == null) {
            if (table.fields.any { it.usesUnsignedArray() }) {
                file.addAnnotation(
                    AnnotationSpec.builder(OPT_IN).addMember("%T::class", EXPERIMENTAL_UNSIGNED_TYPES).build()
                )
            }
            // Nested tables and structs from other namespaces have their copyInto in their own package. KotlinPoet
            // won't import a name this file also declares, so import it by hand; Kotlin picks the
            // overload by receiver type.
            table.fields.mapNotNull { it.nestedType()?.packageName }
                .filter { it != table.type.packageName }
                .distinct()
                .forEach { file.addImport(it, "copyInto") }
            file.addFunction(pack(table, tableClass))
            file.addFunction(copyInto(table, tableClass))
            file.addFunction(build(table, tableClass, bytes = false))
            file.addFunction(build(table, tableClass, bytes = true))
        }
        return file.build()
    }

    private fun fromBytes(tableClass: ClassName) =
        FunSpec.builder("fromBytes")
            .addKdoc("Reads a %T message from the bytes of a finished buffer, without copying them.", tableClass)
            .receiver(tableClass.companion())
            .addParameter("bytes", BYTE_ARRAY)
            .returns(tableClass)
            .addStatement("return %T.getRootAs%L(%T.wrap(bytes))", tableClass, tableClass.simpleName, BYTE_BUFFER)
            .build()

    private fun pack(table: TableDef, tableClass: ClassName): FunSpec {
        val body = CodeBlock.builder()
        // Strings, vectors and nested tables must all be written before the table is started.
        for (field in table.fields) {
            val name = field.kotlinName()
            val offset = offsetVal(field)
            val create: CodeBlock = when (val kind = field.kind) {
                // Structs are written inline, right before their add call (see below).
                is FieldKind.Scalar, is FieldKind.Struct -> continue
                FieldKind.Str -> CodeBlock.of("builder.createString(%L)", "it")
                is FieldKind.Table -> CodeBlock.of("it.copyInto(builder)")
                is FieldKind.ScalarVector -> CodeBlock.of(
                    "%T.create%LVector(builder, it.to%L())", tableClass, field.vectorName(), kind.element.type.arrayType,
                )
                FieldKind.StrVector -> CodeBlock.of(
                    "%T.create%LVector(builder, IntArray(it.size) { i -> builder.createString(it[i]) })",
                    tableClass, field.vectorName(),
                )
                is FieldKind.TableVector -> CodeBlock.of(
                    "%T.create%LVector(builder, IntArray(it.size) { i -> it[i].copyInto(builder) })",
                    tableClass, field.vectorName(),
                )
                // flatc has no create<Field>Vector for structs: they're written in place, last first.
                is FieldKind.StructVector -> CodeBlock.of(
                    "%T.start%LVector(builder, it.size); it.asReversed().forEach { s -> s.copyInto(builder) }; builder.endVector()",
                    tableClass, field.vectorName(),
                )
            }
            if (field.required) {
                body.addStatement("val %L = %L.let { %L }", offset, name, create)
            } else {
                body.addStatement("val %L = %L?.let { %L }", offset, name, create)
            }
        }
        body.addStatement("%T.start%L(builder)", tableClass, tableClass.simpleName)
        for (field in table.fields) {
            val add = "add${field.capitalizedName()}"
            when {
                // flatc's add<Field> for an optional scalar passes the type's zero as the default,
                // so FlatBuffers would silently drop a value equal to it. Write the slot directly.
                field.kind is FieldKind.Scalar && field.optionalScalar ->
                    body.addStatement(
                        "%L?.let { builder.%L; builder.slot(%L) }",
                        field.kotlinName(), rawAdd(field.kind.type), field.id,
                    )
                field.kind is FieldKind.Scalar ->
                    body.addStatement("%T.%L(builder, %L)", tableClass, add, field.kotlinName())
                // A struct must be written immediately before the add call that places it.
                field.kind is FieldKind.Struct && field.required ->
                    body.addStatement("%T.%L(builder, %L.copyInto(builder))", tableClass, add, field.kotlinName())
                field.kind is FieldKind.Struct ->
                    body.addStatement("%L?.let { %T.%L(builder, it.copyInto(builder)) }", field.kotlinName(), tableClass, add)
                field.required ->
                    body.addStatement("%T.%L(builder, %L)", tableClass, add, offsetVal(field))
                else ->
                    body.addStatement("%L?.let { %T.%L(builder, it) }", offsetVal(field), tableClass, add)
            }
        }
        body.addStatement("return %T.end%L(builder)", tableClass, tableClass.simpleName)

        return FunSpec.builder("pack")
            .addKdoc(
                "Writes a %T message into [builder] and returns its offset, for building larger messages by hand. " +
                    "Nested messages are copied in.",
                tableClass,
            )
            .receiver(tableClass.companion())
            .addParameter("builder", FLAT_BUFFER_BUILDER)
            .addParameters(table.fields.map { it.parameter() })
            .returns(INT)
            .addCode(body.build())
            .build()
    }

    private fun copyInto(table: TableDef, tableClass: ClassName): FunSpec {
        val args = table.fields.map { field ->
            val name = field.kotlinName()
            val value = when (field.kind) {
                is FieldKind.Scalar, FieldKind.Str, is FieldKind.Table, is FieldKind.Struct -> CodeBlock.of("this.%L", name)
                is FieldKind.ScalarVector, FieldKind.StrVector, is FieldKind.TableVector, is FieldKind.StructVector -> {
                    // Elements of string, table and struct vectors are never null in a valid buffer.
                    val element = if (field.kind is FieldKind.ScalarVector) "this.$name(it)" else "this.$name(it)!!"
                    val list = CodeBlock.of("List(this.%LLength) { %L }", name, element)
                    // An absent vector and an empty one read the same; optional ones are written
                    // back as absent.
                    if (field.required) list else CodeBlock.of("if (this.%LLength == 0) null else %L", name, list)
                }
            }
            // flatc's accessors for required fields are non-null (and throw if the field is missing).
            CodeBlock.of("%L = %L", name, value)
        }
        return FunSpec.builder("copyInto")
            .addKdoc("Writes a copy of this message into [builder] and returns its offset.")
            .receiver(tableClass)
            .addParameter("builder", FLAT_BUFFER_BUILDER)
            .returns(INT)
            .addCode(
                CodeBlock.builder()
                    .add("return %T.pack(\n", tableClass)
                    .indent()
                    .add("builder,\n")
                    .apply { args.forEach { add("%L,\n", it) } }
                    .unindent()
                    .add(")\n")
                    .build()
            )
            .build()
    }

    private fun build(table: TableDef, tableClass: ClassName, bytes: Boolean): FunSpec {
        val finish = if (table.fileIdentifier != null) {
            CodeBlock.of("builder.finish(%T.pack(builder, %L), %S)", tableClass, packArgs(table), table.fileIdentifier)
        } else {
            CodeBlock.of("builder.finish(%T.pack(builder, %L))", tableClass, packArgs(table))
        }
        return FunSpec.builder(if (bytes) "buildBytes" else "build")
            .addKdoc(
                if (bytes) "Builds a finished %T message and returns its bytes." else "Builds a finished %T message.",
                tableClass,
            )
            .receiver(tableClass.companion())
            .addParameters(table.fields.map { it.parameter() })
            .returns(if (bytes) BYTE_ARRAY else tableClass)
            .addStatement("val builder = %T()", FLAT_BUFFER_BUILDER)
            .addStatement("%L", finish)
            .apply {
                if (bytes) addStatement("return builder.sizedByteArray()")
                else addStatement("return %T.getRootAs%L(builder.dataBuffer())", tableClass, tableClass.simpleName)
            }
            .build()
    }

    private fun packArgs(table: TableDef): CodeBlock =
        table.fields.map { CodeBlock.of("%L = %L", it.kotlinName(), it.kotlinName()) }.joinToCode()

    private fun FieldDef.parameter(): ParameterSpec {
        val type = kind.parameterType().copy(nullable = !required && (kind !is FieldKind.Scalar || optionalScalar))
        val param = ParameterSpec.builder(kotlinName(), type)
        when {
            required -> Unit
            kind is FieldKind.Scalar && !optionalScalar -> {
                val enumType = kind.enumType
                if (enumType != null && defaultEnumValue != null) {
                    param.defaultValue("%T.%L", enumType.toClassName(), defaultEnumValue)
                } else {
                    param.defaultValue("%L", defaultValue)
                }
            }
            else -> param.defaultValue("null")
        }
        return param.build()
    }

    private fun FieldKind.parameterType(): TypeName = when (this) {
        is FieldKind.Scalar -> type.typeName()
        FieldKind.Str -> STRING
        is FieldKind.Table -> type.toClassName()
        is FieldKind.ScalarVector -> LIST.parameterizedBy(element.type.typeName())
        FieldKind.StrVector -> LIST.parameterizedBy(STRING)
        is FieldKind.TableVector -> LIST.parameterizedBy(element.toClassName())
        is FieldKind.Struct -> type.toClassName()
        is FieldKind.StructVector -> LIST.parameterizedBy(element.toClassName())
    }

    /** FlatBufferBuilder's untyped-slot `add` call for a scalar named `it`. */
    private fun rawAdd(type: ScalarType): String = when (type) {
        ScalarType.BOOL -> "addBoolean(it)"
        ScalarType.BYTE -> "addByte(it)"
        ScalarType.UBYTE -> "addByte(it.toByte())"
        ScalarType.SHORT -> "addShort(it)"
        ScalarType.USHORT -> "addShort(it.toShort())"
        ScalarType.INT -> "addInt(it)"
        ScalarType.UINT -> "addInt(it.toInt())"
        ScalarType.LONG -> "addLong(it)"
        ScalarType.ULONG -> "addLong(it.toLong())"
        ScalarType.FLOAT -> "addFloat(it)"
        ScalarType.DOUBLE -> "addDouble(it)"
    }

    /** A table or struct this field holds, whose generated copyInto the builders call. */
    private fun FieldDef.nestedType(): FbsTypeName? = when (val kind = kind) {
        is FieldKind.Table -> kind.type
        is FieldKind.TableVector -> kind.element
        is FieldKind.Struct -> kind.type
        is FieldKind.StructVector -> kind.element
        else -> null
    }

    private fun FieldDef.usesUnsignedArray(): Boolean {
        val vector = kind as? FieldKind.ScalarVector ?: return false
        return vector.element.type in setOf(ScalarType.UBYTE, ScalarType.USHORT, ScalarType.UINT, ScalarType.ULONG)
    }

    private fun FieldDef.kotlinName(): String = FlatcNames.accessor(name)

    private fun FieldDef.capitalizedName() = kotlinName().replaceFirstChar { it.uppercaseChar() }

    /** flatc's `create<Name>Vector` doesn't get the keyword suffix: `when` gives `createWhenVector`. */
    private fun FieldDef.vectorName() = FlatcNames.camel(name).replaceFirstChar { it.uppercaseChar() }

    /** Field names never contain `_` after camel-casing (bar a keyword suffix), so this can't clash. */
    private fun offsetVal(field: FieldDef) = "o_${field.kotlinName()}"

    private fun List<CodeBlock>.joinToCode(): CodeBlock = CodeBlock.builder().apply {
        forEachIndexed { i, block -> if (i > 0) add(", "); add(block) }
    }.build()
}
