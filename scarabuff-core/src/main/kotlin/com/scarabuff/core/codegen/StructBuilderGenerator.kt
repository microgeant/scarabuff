package com.scarabuff.core.codegen

import com.scarabuff.core.fbs.FbsTypeName
import com.scarabuff.core.fbs.FieldKind
import com.scarabuff.core.fbs.StructDef
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.joinToCode
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec

/**
 * Generates `<Struct>Builders.kt` for one struct:
 *
 * - `Vec2Spec(x, y)`: the struct as a data class. Table builders take it for struct fields and
 *   write it inline with [writeTo], with no buffer of its own. A struct always has every field, so
 *   every property is required.
 * - `Vec2Spec.Vector(n) { i -> put(xs[i], ys[i]) }`: a `List<Vec2Spec>` for vector fields whose
 *   elements are produced while the message is written, so no object is allocated per struct.
 * - `vec.toSpec()`: reads a received struct into a spec.
 * - `vec.copyInto(builder)`: writes a received struct inline into a message being built. Inside a
 *   table it must come immediately before the `add` call that places it.
 * - `Vec2.build(...)`, deprecated: it allocates a builder per struct. Use the spec instead.
 *
 * flatc's `create<Struct>` takes nested structs' fields flattened in declaration order
 * (`createPose(builder, position_x, position_y, angle)`), so nested values are expanded here.
 */
object StructBuilderGenerator {

    private val FLAT_BUFFER_BUILDER = ClassName("com.google.flatbuffers", "FlatBufferBuilder")

    /** The generated `<Struct>Spec.Vector`, which table builders write without an object per element. */
    fun vectorClassName(type: FbsTypeName) = TableBuilderGenerator.specClassName(type).nestedClass("Vector")

    /** @param structs every struct in the schema, to expand nested struct fields */
    fun generate(struct: StructDef, structs: Map<FbsTypeName, StructDef>): FileSpec {
        val structClass = struct.type.toClassName()
        val file = FileSpec.builder(struct.type.packageName, "${struct.type.simpleName}Builders")
        // Nested structs from other namespaces have their toSpec in their own package; see
        // TableBuilderGenerator for why it's imported by hand.
        struct.fields.mapNotNull { (it.kind as? FieldKind.Struct)?.type?.packageName }
            .filter { it != struct.type.packageName }
            .distinct()
            .forEach { file.addImport(it, "toSpec") }
        return file
            .addType(spec(struct, structClass, structs))
            .addFunction(toSpec(struct, structClass))
            .addFunction(copyInto(struct, structClass, structs))
            .addFunction(deprecatedBuild(struct, structClass, structs))
            .build()
    }

    /** One per field; nested structs are specs, or flatc's struct views for the deprecated `build`. */
    private fun parameters(struct: StructDef, specs: Boolean = true): List<ParameterSpec> = struct.fields.map { field ->
        val type: TypeName = when (val kind = field.kind) {
            is FieldKind.Scalar -> kind.type.typeName()
            is FieldKind.Struct -> if (specs) TableBuilderGenerator.specClassName(kind.type) else kind.type.toClassName()
            else -> error("Unexpected kind ${field.kind} in struct ${struct.type}")
        }
        ParameterSpec.builder(FlatcNames.accessor(field.name), type).build()
    }

    private fun spec(struct: StructDef, structClass: ClassName, structs: Map<FbsTypeName, StructDef>): TypeSpec {
        val parameters = parameters(struct)
        return TypeSpec.classBuilder(TableBuilderGenerator.specClassName(struct.type))
            .addModifiers(KModifier.DATA)
            .addKdoc(
                "A %T struct as plain Kotlin values. Table builders write it inline, with no buffer of its own.",
                structClass,
            )
            .primaryConstructor(FunSpec.constructorBuilder().addParameters(parameters).build())
            .addProperties(parameters.map { PropertySpec.builder(it.name, it.type).initializer(it.name).build() })
            .addFunction(
                FunSpec.builder("writeTo")
                    .addKdoc(
                        "Writes this struct inline into [builder] and returns its offset. Inside a table it must " +
                            "come immediately before the `add` call that places it, as the generated builders do."
                    )
                    .addParameter("builder", FLAT_BUFFER_BUILDER)
                    .returns(INT)
                    .addStatement(
                        "return %T.create%L(builder, %L)",
                        structClass, structClass.simpleName, flatten(struct, null, structs, fromSpec = true).joinToString(", "),
                    )
                    .build()
            )
            .addType(vector(struct, structClass, structs))
            .build()
    }

    /** `<Struct>Spec.Vector`; see [SpecVectorGenerator]. `put` takes nested structs' fields flattened. */
    private fun vector(struct: StructDef, structClass: ClassName, structs: Map<FbsTypeName, StructDef>): TypeSpec {
        val spec = TableBuilderGenerator.specClassName(struct.type)
        val flat = flatParameters(struct, structs)
        val names = flat.joinToString(", ") { it.name }
        return SpecVectorGenerator.generate(
            spec = spec,
            typeName = structClass.simpleName,
            kind = "struct",
            putParameters = flat.map { ParameterSpec.builder(it.name, it.type).build() },
            putKdoc = if (flat.any { it.nested }) "Gives the struct's values, nested structs' fields flattened." else "Gives the struct's values.",
            example = if (flat.size <= 3) names else flat.take(2).joinToString(", ") { it.name } + ", …",
            order = "from the last index to the first",
            write = CodeBlock.of("%T.create%L(_builder, %L)", structClass, structClass.simpleName, names),
            record = specOf(struct, structs, flat.iterator()),
        ) { vector, writer ->
            listOf(
                FunSpec.builder("writeElements")
                    .addKdoc(
                        "Writes [specs] into a vector already started in [builder], last first, as FlatBuffers " +
                            "requires. A [%T] writes without an object per struct.",
                        vector,
                    )
                    .addParameter("builder", FLAT_BUFFER_BUILDER)
                    .addParameter("specs", LIST.parameterizedBy(spec))
                    .beginControlFlow("if (specs is %T)", vector)
                    .addStatement("val _writer = %T(builder)", writer)
                    .addStatement("for (i in specs.size - 1 downTo 0) specs.write(_writer, i)")
                    .nextControlFlow("else")
                    .addStatement("for (i in specs.size - 1 downTo 0) specs[i].writeTo(builder)")
                    .endControlFlow()
                    .build()
            )
        }
    }

    /** One of `put`'s parameters: a scalar of the struct, from a nested struct when [nested]. */
    private class FlatParameter(val name: String, val type: TypeName, val nested: Boolean)

    /**
     * Every scalar in [struct], in flatc's `create<Struct>` order. Nested fields are named by
     * their path in lowerCamelCase (`position.x` is `positionX`), or with `_` (`position_x`) if
     * that clashes with another field; plain accessors never contain `_` (bar a keyword suffix).
     */
    private fun flatParameters(struct: StructDef, structs: Map<FbsTypeName, StructDef>): List<FlatParameter> {
        val paths = scalarPaths(struct, structs, emptyList())
        val camel = paths.map { (path, _) ->
            if (path.size == 1) FlatcNames.accessor(path[0])
            else FlatcNames.camel(path[0]) + path.drop(1).joinToString("") { p -> FlatcNames.camel(p).replaceFirstChar { it.uppercaseChar() } }
        }
        val names = if (camel.toSet().size == camel.size) camel else paths.map { (path, _) -> path.joinToString("_") { FlatcNames.accessor(it) } }
        return paths.zip(names) { (path, type), name -> FlatParameter(name, type, path.size > 1) }
    }

    private fun scalarPaths(struct: StructDef, structs: Map<FbsTypeName, StructDef>, prefix: List<String>): List<Pair<List<String>, TypeName>> =
        struct.fields.flatMap { field ->
            when (val kind = field.kind) {
                is FieldKind.Struct -> scalarPaths(structs.getValue(kind.type), structs, prefix + field.name)
                is FieldKind.Scalar -> listOf((prefix + field.name) to kind.type.typeName())
                else -> error("Unexpected kind ${field.kind} in struct ${struct.type}")
            }
        }

    /** `PoseSpec(Vec2Spec(positionX, positionY), angle)`, consuming [flat] in declaration order. */
    private fun specOf(struct: StructDef, structs: Map<FbsTypeName, StructDef>, flat: Iterator<FlatParameter>): CodeBlock {
        val args = struct.fields.map { field ->
            when (val kind = field.kind) {
                is FieldKind.Struct -> specOf(structs.getValue(kind.type), structs, flat)
                else -> CodeBlock.of("%L", flat.next().name)
            }
        }
        return CodeBlock.of("%T(%L)", TableBuilderGenerator.specClassName(struct.type), args.joinToCode(", "))
    }

    private fun toSpec(struct: StructDef, structClass: ClassName): FunSpec {
        val args = struct.fields.map { field ->
            val name = FlatcNames.accessor(field.name)
            // flatc's accessors for nested structs are nullable but always present.
            if (field.kind is FieldKind.Struct) "$name = this.$name!!.toSpec()" else "$name = this.$name"
        }
        return FunSpec.builder("toSpec")
            .addKdoc("Reads this struct into a [%T].", TableBuilderGenerator.specClassName(struct.type))
            .receiver(structClass)
            .returns(TableBuilderGenerator.specClassName(struct.type))
            .addStatement("return %T(%L)", TableBuilderGenerator.specClassName(struct.type), args.joinToString(", "))
            .build()
    }

    private fun copyInto(struct: StructDef, structClass: ClassName, structs: Map<FbsTypeName, StructDef>) =
        FunSpec.builder("copyInto")
            .addKdoc(
                "Writes this struct inline into [builder] and returns its offset. Inside a table it must be " +
                    "called immediately before the `add` call that places it."
            )
            .receiver(structClass)
            .addParameter("builder", FLAT_BUFFER_BUILDER)
            .returns(INT)
            .addStatement(
                "return %T.create%L(builder, %L)",
                structClass, structClass.simpleName, flatten(struct, "this", structs, fromSpec = false).joinToString(", "),
            )
            .build()

    /** The 0.4.0 way to make a struct value. Kept for one release so existing code still compiles. */
    private fun deprecatedBuild(struct: StructDef, structClass: ClassName, structs: Map<FbsTypeName, StructDef>): FunSpec {
        val spec = TableBuilderGenerator.specClassName(struct.type)
        val args = parameters(struct).joinToString(", ") { it.name }
        return FunSpec.builder("build")
            .addKdoc("Creates a standalone %T value in a buffer of its own.", structClass)
            .addAnnotation(
                AnnotationSpec.builder(Deprecated::class)
                    .addMember(
                        "%S",
                        "Allocates a FlatBufferBuilder per struct. Table builders take ${spec.simpleName} instead, " +
                            "which they write inline.",
                    )
                    .addMember("ReplaceWith(%S, %S)", "${spec.simpleName}($args)", spec.canonicalName)
                    .build()
            )
            .receiver(structClass.companion())
            .addParameters(parameters(struct, specs = false))
            .returns(structClass)
            // The builder only hands out its buffer once finished, so finish it with the struct as
            // the root, then find the struct through the root offset at the start of the buffer.
            // Local names start with `_`, which no flatc field accessor does, so they can't clash.
            .addStatement("val _builder = %T()", FLAT_BUFFER_BUILDER)
            .addStatement(
                "_builder.finish(%T.create%L(_builder, %L))",
                structClass, structClass.simpleName, flatten(struct, null, structs, fromSpec = false).joinToString(", "),
            )
            .addStatement("val _buffer = _builder.dataBuffer()")
            .addStatement("return %T().__assign(_buffer.position() + _buffer.getInt(_buffer.position()), _buffer)", structClass)
            .build()
    }

    /**
     * Expressions for every scalar in [struct], in the order flatc's `create<Struct>` takes them.
     * [root] is the struct value they're read from (`this`), or null for the properties or
     * parameters in scope. Nested values are specs ([fromSpec]), or flatc struct views, whose
     * accessors for nested structs are nullable but always present: those get `!!`, while a
     * parameter or spec property is already non-null.
     */
    private fun flatten(struct: StructDef, root: String?, structs: Map<FbsTypeName, StructDef>, fromSpec: Boolean): List<String> =
        struct.fields.flatMap { field ->
            val name = FlatcNames.accessor(field.name)
            val access = if (root == null) name else "$root.$name"
            when (val kind = field.kind) {
                is FieldKind.Struct -> {
                    val nested = if (!fromSpec && root != null) "$access!!" else access
                    flatten(structs.getValue(kind.type), nested, structs, fromSpec)
                }
                else -> listOf(access)
            }
        }
}
