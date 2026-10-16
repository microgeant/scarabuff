package com.scarabuff.core.codegen

import com.scarabuff.core.fbs.FbsTypeName
import com.scarabuff.core.fbs.FieldDef
import com.scarabuff.core.fbs.FieldKind
import com.scarabuff.core.fbs.ScalarType
import com.scarabuff.core.fbs.StructDef
import com.scarabuff.core.fbs.TableDef
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.BYTE_ARRAY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec

/**
 * Generates `<Table>Builders.kt` for one table: a plain-value form of the message and functions
 * that write it with flatc's own `start`/`add`/`end`/`create*Vector` calls, so the wire format is
 * exactly what flatc would produce. flatc's Kotlin backend has no object API, so without these
 * every message needs hand-written `FlatBufferBuilder` calls.
 *
 * For `table Post { title:string; author:User; }` this emits:
 * - `PostSpec(title, author: UserSpec?)`: a data class holding the message as ordinary values,
 *   with `writeTo(builder)`, `build()` and `buildBytes()`. Nested tables and structs are specs
 *   too, so a whole message tree is written into one `FlatBufferBuilder`, with no intermediate
 *   buffer per nested message.
 * - `Post.build(...)`, `Post.buildBytes(...)` and `Post.pack(builder, ...)`: the same, taking the
 *   fields as named arguments.
 * - `post.toSpec()`, to turn a received message into a spec (decoding it), and
 *   `post.copyInto(builder)` to re-serialize it into another message.
 * - `Post.fromBytes(bytes)`, which every table gets, even one with no builders.
 *
 * Required fields are non-null with no default; everything else defaults to the schema's default
 * (or null).
 */
object TableBuilderGenerator {

    private val FLAT_BUFFER_BUILDER = ClassName("com.google.flatbuffers", "FlatBufferBuilder")
    private val BYTE_BUFFER = ClassName("java.nio", "ByteBuffer")
    private val EXPERIMENTAL_UNSIGNED_TYPES = ClassName("kotlin", "ExperimentalUnsignedTypes")
    private val OPT_IN = ClassName("kotlin", "OptIn")

    /** The generated plain-value class for a table or struct: `User` gets `UserSpec`. */
    fun specClassName(type: FbsTypeName) = ClassName(type.packageName, "${type.simpleName}Spec")

    /**
     * @param fieldAccess the generated [FieldAccessGenerator] object, which the string helpers use
     *   to check whether an optional field is present
     */
    fun generate(
        table: TableDef,
        fieldAccess: ClassName = FieldAccessGenerator.className(emptyList()),
        tables: Map<FbsTypeName, TableDef> = emptyMap(),
        structs: Map<FbsTypeName, StructDef> = emptyMap(),
    ): FileSpec {
        val tableClass = table.type.toClassName()
        val sizing = Sizing(table, tables, structs)
        val file = FileSpec.builder(table.type.packageName, "${table.type.simpleName}Builders")
            .addFunction(fromBytes(tableClass))
        val strings = table.fields.filter { it.kind == FieldKind.Str }
        if (strings.isNotEmpty()) {
            strings.forEach { file.addFunction(stringEquals(it, tableClass, fieldAccess)) }
        }
        if (table.unsupportedReason == null) {
            if (table.fields.any { it.usesUnsignedArray() }) {
                file.addAnnotation(
                    AnnotationSpec.builder(OPT_IN).addMember("%T::class", EXPERIMENTAL_UNSIGNED_TYPES).build()
                )
            }
            // Nested tables and structs from other namespaces have their toSpec in their own
            // package. KotlinPoet won't import a name this file also declares, so import it by
            // hand; Kotlin picks the overload by receiver type.
            table.fields.mapNotNull { it.nestedType()?.packageName }
                .filter { it != table.type.packageName }
                .distinct()
                .forEach { file.addImport(it, "toSpec") }
            file.addType(spec(table, tableClass, sizing))
            file.addFunction(sizeHint(tableClass, sizing))
            file.addFunction(pack(table, tableClass))
            file.addFunction(build(table, tableClass, sizing, bytes = false))
            file.addFunction(build(table, tableClass, sizing, bytes = true))
            file.addFunction(toSpec(table, tableClass))
            file.addFunction(copyInto(tableClass))
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

    /**
     * `<field>Equals(value)`: compares a string field without decoding it into a `String` (each
     * read of flatc's accessor allocates one). The work happens in [fieldAccess], which reads the
     * message buffer directly: one presence check and a byte compare, with no `ByteBuffer` slice.
     * flatc's own `<field>AsByteBuffer` would also throw for an absent field.
     */
    private fun stringEquals(field: FieldDef, tableClass: ClassName, fieldAccess: ClassName): FunSpec {
        val name = field.kotlinName()
        val fn = FunSpec.builder("${name}Equals")
            .addKdoc(
                "Whether `%L` equals [value], compared as UTF-8 bytes without decoding the field into a " +
                    "`String`. A null [value] matches an absent field.",
                name,
            )
            .receiver(tableClass)
            .addParameter("value", STRING.copy(nullable = true))
            .returns(BOOLEAN)
        // A field's vtable slot is at 4 + 2 * its id.
        return fn.addStatement("return %T.stringEquals(this, %L, value)", fieldAccess, 4 + 2 * field.id).build()
    }

    /**
     * `<Table>Spec`: a data class with one property per field. A table with no fields gets a plain
     * class instead, since a data class needs at least one property.
     */
    private fun spec(table: TableDef, tableClass: ClassName, sizing: Sizing): TypeSpec {
        val specClass = specClassName(table.type)
        val parameters = table.fields.map { it.parameter() }
        val forward = packArgs(table)
        val type = TypeSpec.classBuilder(specClass)
            .addKdoc(
                "A %T message as plain Kotlin values. Nested tables and structs are specs too, so [writeTo] " +
                    "writes the whole tree into one builder.",
                tableClass,
            )
            .primaryConstructor(FunSpec.constructorBuilder().addParameters(parameters).build())
            .addProperties(parameters.map { PropertySpec.builder(it.name, it.type).initializer(it.name).build() })
            .addFunction(
                FunSpec.builder("sizeHint")
                    .addModifiers(KModifier.INTERNAL)
                    .addKdoc("An estimate of this message's encoded size, without its vtable; see `%T.sizeHint`.", tableClass)
                    .returns(INT)
                    .addStatement("return %T.sizeHint(%L)", tableClass, sizing.args())
                    .build()
            )
            .addFunction(
                FunSpec.builder("writeTo")
                    .addKdoc("Writes this message into [builder] and returns its offset.")
                    .addParameter("builder", FLAT_BUFFER_BUILDER)
                    .returns(INT)
                    .addStatement("return %T.pack(builder%L)", tableClass, forward.prefixed())
                    .build()
            )
            .addFunction(
                FunSpec.builder("build")
                    .addKdoc(buildKdoc(bytes = false), tableClass)
                    .addParameter(builderParameter(CodeBlock.of("sizeHint() + %L", sizing.rootOverhead)))
                    .returns(tableClass)
                    .addStatement("return %T.build(%Lbuilder = builder)", tableClass, forward.suffixed())
                    .build()
            )
            .addFunction(
                FunSpec.builder("buildBytes")
                    .addKdoc(buildKdoc(bytes = true), tableClass)
                    .addParameter(builderParameter(CodeBlock.of("sizeHint() + %L", sizing.rootOverhead)))
                    .returns(BYTE_ARRAY)
                    .addStatement("return %T.buildBytes(%Lbuilder = builder)", tableClass, forward.suffixed())
                    .build()
            )
        if (table.fields.isEmpty()) {
            // What a data class would provide: all instances of an empty message are equal.
            type.addFunction(
                FunSpec.builder("equals").addModifiers(KModifier.OVERRIDE)
                    .addParameter("other", ANY.copy(nullable = true)).returns(BOOLEAN)
                    .addStatement("return other is %T", specClass).build()
            )
            type.addFunction(
                FunSpec.builder("hashCode").addModifiers(KModifier.OVERRIDE).returns(INT)
                    .addStatement("return 0").build()
            )
            type.addFunction(
                FunSpec.builder("toString").addModifiers(KModifier.OVERRIDE).returns(STRING)
                    .addStatement("return %S", "${specClass.simpleName}()").build()
            )
        } else {
            type.addModifiers(KModifier.DATA)
        }
        return type.build()
    }

    // Generated lambdas are glued to what precedes them with `·`, KotlinPoet's non-breaking space:
    // KotlinPoet wraps long lines at ordinary spaces, and a line break before a lambda's `{` (or
    // between its parameter and `->`) is a compile error. Breaks after `{` and `->` are fine.

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
                is FieldKind.Table -> CodeBlock.of("it.writeTo(builder)")
                is FieldKind.ScalarVector -> CodeBlock.of(
                    "%T.create%LVector(builder, it.to%L())", tableClass, field.vectorName(), kind.element.type.arrayType,
                )
                FieldKind.StrVector -> CodeBlock.of(
                    "%T.create%LVector(builder, IntArray(it.size)·{·i·-> builder.createString(it[i]) })",
                    tableClass, field.vectorName(),
                )
                is FieldKind.TableVector -> CodeBlock.of(
                    "%T.create%LVector(builder, IntArray(it.size)·{·i·-> it[i].writeTo(builder) })",
                    tableClass, field.vectorName(),
                )
                // flatc has no create<Field>Vector for structs: they're written in place, last first,
                // by the struct's generated Vector, without a spec per element when given one.
                is FieldKind.StructVector -> CodeBlock.of(
                    "%T.start%LVector(builder, it.size); %T.writeElements(builder, it); builder.endVector()",
                    tableClass, field.vectorName(), StructBuilderGenerator.vectorClassName(kind.element),
                )
            }
            if (field.required) {
                body.addStatement("val %L = %L.let·{ %L }", offset, name, create)
            } else {
                body.addStatement("val %L = %L?.let·{ %L }", offset, name, create)
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
                        "%L?.let·{ builder.%L; builder.slot(%L) }",
                        field.kotlinName(), rawAdd(field.kind.type), field.id,
                    )
                field.kind is FieldKind.Scalar ->
                    body.addStatement("%T.%L(builder, %L)", tableClass, add, field.kotlinName())
                // A struct must be written immediately before the add call that places it.
                field.kind is FieldKind.Struct && field.required ->
                    body.addStatement("%T.%L(builder, %L.writeTo(builder))", tableClass, add, field.kotlinName())
                field.kind is FieldKind.Struct ->
                    body.addStatement("%L?.let·{ %T.%L(builder, it.writeTo(builder)) }", field.kotlinName(), tableClass, add)
                field.required ->
                    body.addStatement("%T.%L(builder, %L)", tableClass, add, offsetVal(field))
                else ->
                    body.addStatement("%L?.let·{ %T.%L(builder, it) }", offsetVal(field), tableClass, add)
            }
        }
        body.addStatement("return %T.end%L(builder)", tableClass, tableClass.simpleName)

        return FunSpec.builder("pack")
            .addKdoc(
                "Writes a %T message into [builder] and returns its offset, for building larger messages by hand. " +
                    "Nested specs are written into the same builder.",
                tableClass,
            )
            .receiver(tableClass.companion())
            .addParameter("builder", FLAT_BUFFER_BUILDER)
            .addParameters(table.fields.map { it.parameter() })
            .returns(INT)
            .addCode(body.build())
            .build()
    }

    private fun build(table: TableDef, tableClass: ClassName, sizing: Sizing, bytes: Boolean): FunSpec {
        val finish = if (table.fileIdentifier != null) {
            CodeBlock.of("builder.finish(%T.pack(builder%L), %S)", tableClass, packArgs(table).prefixed(), table.fileIdentifier)
        } else {
            CodeBlock.of("builder.finish(%T.pack(builder%L))", tableClass, packArgs(table).prefixed())
        }
        return FunSpec.builder(if (bytes) "buildBytes" else "build")
            .addKdoc(buildKdoc(bytes), tableClass)
            .receiver(tableClass.companion())
            .addParameters(table.fields.map { it.parameter() })
            .addParameter(builderParameter(CodeBlock.of("%T.sizeHint(%L) + %L", tableClass, sizing.args(), sizing.rootOverhead)))
            .returns(if (bytes) BYTE_ARRAY else tableClass)
            .addStatement("builder.clear()")
            .addStatement("%L", finish)
            .apply {
                if (bytes) addStatement("return builder.sizedByteArray()")
                else addStatement("return %T.getRootAs%L(builder.dataBuffer())", tableClass, tableClass.simpleName)
            }
            .build()
    }

    /** Reads every field of a received message into a spec, decoding strings and nested messages. */
    private fun toSpec(table: TableDef, tableClass: ClassName): FunSpec {
        val args = table.fields.map { field ->
            val name = field.kotlinName()
            val value = when (field.kind) {
                is FieldKind.Scalar, FieldKind.Str -> CodeBlock.of("this.%L", name)
                // flatc's accessors for required fields are non-null (and throw if the field is missing).
                is FieldKind.Table, is FieldKind.Struct ->
                    CodeBlock.of(if (field.required) "this.%L.toSpec()" else "this.%L?.toSpec()", name)
                is FieldKind.ScalarVector, FieldKind.StrVector, is FieldKind.TableVector, is FieldKind.StructVector -> {
                    // Elements of string, table and struct vectors are never null in a valid buffer.
                    val element = when (field.kind) {
                        is FieldKind.ScalarVector -> "this.$name(it)"
                        FieldKind.StrVector -> "this.$name(it)!!"
                        else -> "this.$name(it)!!.toSpec()"
                    }
                    val list = CodeBlock.of("List(this.%LLength)·{ %L }", name, element)
                    // An absent vector and an empty one read the same; optional ones become null.
                    if (field.required) list else CodeBlock.of("if (this.%LLength == 0) null else %L", name, list)
                }
            }
            CodeBlock.of("%L = %L", name, value)
        }
        return FunSpec.builder("toSpec")
            .addKdoc("Reads this message into a [%T], decoding every field.", specClassName(table.type))
            .receiver(tableClass)
            .returns(specClassName(table.type))
            .addCode(
                CodeBlock.builder()
                    .add("return %T(\n", specClassName(table.type))
                    .indent()
                    .apply { args.forEach { add("%L,\n", it) } }
                    .unindent()
                    .add(")\n")
                    .build()
            )
            .build()
    }

    private fun copyInto(tableClass: ClassName) =
        FunSpec.builder("copyInto")
            .addKdoc("Writes a copy of this message into [builder] and returns its offset.")
            .receiver(tableClass)
            .addParameter("builder", FLAT_BUFFER_BUILDER)
            .returns(INT)
            .addStatement("return toSpec().writeTo(builder)")
            .build()

    /**
     * `builder`, optional and last, so callers can presize or reuse it. No field can be called
     * `builder`: tables with one get no builders (see BfbsReader).
     */
    private fun builderParameter(initialSize: CodeBlock) =
        ParameterSpec.builder("builder", FLAT_BUFFER_BUILDER).defaultValue("%T(%L)", FLAT_BUFFER_BUILDER, initialSize).build()

    /**
     * `T.sizeHint(...)`: an estimate of a table's encoded size without its vtable, from the
     * values of its variable-size fields, so builders can start at about the right size instead
     * of FlatBufferBuilder's default 1 KB. A low estimate only means the builder grows, as it
     * would anyway; strings count one byte per char, exact for ASCII.
     *
     * Kept cheap: a vector of tables is estimated from its first element times the count, so a
     * 1000-item list doesn't pay for 1000 size computations. Locals start with `_`, which no
     * field accessor does, so they can't shadow a parameter.
     */
    private fun sizeHint(tableClass: ClassName, sizing: Sizing): FunSpec {
        val fn = FunSpec.builder("sizeHint")
            .addModifiers(KModifier.INTERNAL)
            .addKdoc(
                "An estimate of a %T message's encoded size, without its vtable, used to size builders.",
                tableClass,
            )
            .receiver(tableClass.companion())
            .addParameters(sizing.variable.map { ParameterSpec.builder(it.kotlinName(), it.parameter().type).build() })
            .returns(INT)
            .addStatement("var _size = %L", sizing.fixed)
        for (field in sizing.variable) {
            val name = field.kotlinName()
            val add = CodeBlock.builder()
            when (val kind = field.kind) {
                // Length prefix, null terminator and padding.
                FieldKind.Str -> add.addStatement("_size += 8 + %L.length", name)
                is FieldKind.Table -> add.addStatement("_size += %L.sizeHint() + %L", name, sizing.vtable(kind.type))
                is FieldKind.ScalarVector ->
                    add.addStatement("_size += 8 + %L * %L.size", kind.element.type.byteSize, name)
                is FieldKind.StructVector ->
                    add.addStatement("_size += 8 + %L * %L.size", sizing.structSize(kind.element), name)
                FieldKind.StrVector -> add.addStatement("_size += 8 + 4 * %L.size", name)
                    .addStatement("for (_s in %L) _size += 8 + _s.length", name)
                is FieldKind.TableVector -> add.addStatement("_size += 8 + 4 * %L.size", name)
                    .beginControlFlow("if (%L.isNotEmpty())", name)
                    .addStatement("_size += %L.size * %L[0].sizeHint() + %L", name, name, sizing.vtable(kind.element))
                    .endControlFlow()
                else -> error("not variable-size: $kind")
            }
            if (field.required) {
                fn.addCode(add.build())
            } else {
                fn.beginControlFlow("if (%L != null)", name).addCode(add.build()).endControlFlow()
            }
        }
        return fn.addStatement("return _size").build()
    }

    /** What a table's size estimate is made of; see [sizeHint]. */
    private class Sizing(
        private val table: TableDef,
        private val tables: Map<FbsTypeName, TableDef>,
        private val structs: Map<FbsTypeName, StructDef>,
    ) {
        /** Fields whose encoded size depends on their value. */
        val variable: List<FieldDef> = table.fields.filter {
            it.kind !is FieldKind.Scalar && it.kind !is FieldKind.Struct
        }

        /**
         * Everything that doesn't depend on values: the table's offset to its vtable, scalars and
         * structs stored inline, a 4-byte offset per variable-size field, and alignment slack.
         */
        val fixed: Int = 4 + 8 + table.fields.sumOf { field ->
            when (val kind = field.kind) {
                is FieldKind.Scalar -> kind.type.byteSize
                is FieldKind.Struct -> structSize(kind.type)
                else -> 4
            }
        }

        /** Added once for the message being built: its vtable, the root offset, alignment and identifier. */
        val rootOverhead: Int = vtable(table) + 4 + 8 + if (table.fileIdentifier != null) 4 else 0

        fun vtable(type: FbsTypeName): Int = tables[type]?.let { vtable(it) } ?: 32

        // One 2-byte slot per field id, deprecated ones included, after the 4-byte header.
        private fun vtable(table: TableDef): Int = 4 + 2 * ((table.fields.maxOfOrNull { it.id } ?: -1) + 1)

        fun structSize(type: FbsTypeName): Int = structs[type]?.byteSize ?: 16

        /** `a = a, b = b` for the variable-size fields, to call `sizeHint`. */
        fun args(): CodeBlock = variable.map { CodeBlock.of("%L = %L", it.kotlinName(), it.kotlinName()) }.joinToCode()

        private fun FieldDef.kotlinName() = FlatcNames.accessor(name)

        private fun List<CodeBlock>.joinToCode(): CodeBlock = CodeBlock.builder().apply {
            forEachIndexed { i, block -> if (i > 0) add(", "); add(block) }
        }.build()
    }

    private fun buildKdoc(bytes: Boolean) = if (bytes) {
        "Builds a finished %T message and returns a copy of its bytes.\n\n" +
            "[builder] is cleared first. Pass a presized one, `FlatBufferBuilder(256)`, to avoid the default\n" +
            "1 KB buffer for small messages, or reuse one: the returned bytes are a copy, so the builder\n" +
            "can be passed straight to the next call."
    } else {
        "Builds a finished %T message.\n\n" +
            "[builder] is cleared first. Pass a presized one, `FlatBufferBuilder(256)`, to avoid the default\n" +
            "1 KB buffer for small messages. The message is a view over the builder's buffer, so don't reuse\n" +
            "the builder until the message is sent and no longer read; reuse one with `buildBytes` instead."
    }

    private fun packArgs(table: TableDef): CodeBlock =
        table.fields.map { CodeBlock.of("%L = %L", it.kotlinName(), it.kotlinName()) }.joinToCode()

    /** `, a = a, b = b` after a leading argument, or nothing for a table without fields. */
    private fun CodeBlock.prefixed(): CodeBlock = if (isEmpty()) this else CodeBlock.of(", %L", this)

    /** `a = a, b = b, ` before a trailing argument, or nothing for a table without fields. */
    private fun CodeBlock.suffixed(): CodeBlock = if (isEmpty()) this else CodeBlock.of("%L, ", this)

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
        is FieldKind.Table -> specClassName(type)
        is FieldKind.ScalarVector -> LIST.parameterizedBy(element.type.typeName())
        FieldKind.StrVector -> LIST.parameterizedBy(STRING)
        is FieldKind.TableVector -> LIST.parameterizedBy(specClassName(element))
        is FieldKind.Struct -> specClassName(type)
        is FieldKind.StructVector -> LIST.parameterizedBy(specClassName(element))
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

    /** A table or struct this field holds, whose generated spec and toSpec the builders use. */
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
