package com.scarabuff.core.codegen

import com.scarabuff.core.fbs.FbsTypeName
import com.scarabuff.core.fbs.FieldKind
import com.scarabuff.core.fbs.StructDef
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.ParameterSpec

/**
 * Generates `<Struct>Builders.kt` for one struct, so struct fields can be passed to the table
 * builders the same way nested tables are:
 *
 * - `Vec2.build(x = 1f, y = 2f): Vec2` creates a standalone struct value. A struct always has
 *   every field, so every parameter is required.
 * - `vec.copyInto(builder): Int` writes a struct inline into a message being built. The table
 *   builders call it right before the `add<Field>` call that places it, which FlatBuffers requires.
 *
 * flatc's `create<Struct>` takes nested structs' fields flattened in declaration order
 * (`createPose(builder, position_x, position_y, angle)`), so nested values are expanded here.
 */
object StructBuilderGenerator {

    private val FLAT_BUFFER_BUILDER = ClassName("com.google.flatbuffers", "FlatBufferBuilder")

    /** @param structs every struct in the schema, to expand nested struct fields */
    fun generate(struct: StructDef, structs: Map<FbsTypeName, StructDef>): FileSpec {
        val structClass = struct.type.toClassName()
        val file = FileSpec.builder(struct.type.packageName, "${struct.type.simpleName}Builders")
        // Nested structs from other namespaces have their copyInto in their own package; see
        // TableBuilderGenerator for why it's imported by hand.
        struct.fields.mapNotNull { (it.kind as? FieldKind.Struct)?.type?.packageName }
            .filter { it != struct.type.packageName }
            .distinct()
            .forEach { file.addImport(it, "copyInto") }
        return file
            .addFunction(build(struct, structClass, structs))
            .addFunction(copyInto(struct, structClass, structs))
            .build()
    }

    private fun build(struct: StructDef, structClass: ClassName, structs: Map<FbsTypeName, StructDef>): FunSpec {
        val parameters = struct.fields.map { field ->
            val type = when (val kind = field.kind) {
                is FieldKind.Scalar -> kind.type.typeName()
                is FieldKind.Struct -> kind.type.toClassName()
                else -> error("Unexpected kind ${field.kind} in struct ${struct.type}")
            }
            ParameterSpec.builder(FlatcNames.accessor(field.name), type).build()
        }
        // The values for create<Struct>, from the parameters: a nested struct parameter is expanded
        // into its own fields.
        val args = struct.fields.flatMap { field ->
            val name = FlatcNames.accessor(field.name)
            when (val kind = field.kind) {
                is FieldKind.Struct -> flatten(structs.getValue(kind.type), name, structs)
                else -> listOf(name)
            }
        }
        // The builder only hands out its buffer once finished, so finish it with the struct as the
        // root, then find the struct through the root offset at the start of the buffer.
        // Local names start with `_`, which no flatc field accessor does, so they can't clash.
        return FunSpec.builder("build")
            .addKdoc("Creates a standalone %T value, for example to pass to a table's `build(...)`.", structClass)
            .receiver(structClass.companion())
            .addParameters(parameters)
            .returns(structClass)
            .addStatement("val _builder = %T()", FLAT_BUFFER_BUILDER)
            .addStatement("_builder.finish(%T.create%L(_builder, %L))", structClass, structClass.simpleName, args.joinToString(", "))
            .addStatement("val _buffer = _builder.dataBuffer()")
            .addStatement("return %T().__assign(_buffer.position() + _buffer.getInt(_buffer.position()), _buffer)", structClass)
            .build()
    }

    private fun copyInto(struct: StructDef, structClass: ClassName, structs: Map<FbsTypeName, StructDef>) =
        FunSpec.builder("copyInto")
            .addKdoc(
                "Writes this struct inline into [builder] and returns its offset. Inside a table it must be " +
                    "called immediately before the `add` call that places it, as the generated table builders do."
            )
            .receiver(structClass)
            .addParameter("builder", FLAT_BUFFER_BUILDER)
            .returns(INT)
            .addStatement(
                "return %T.create%L(builder, %L)",
                structClass, structClass.simpleName, flatten(struct, "this", structs).joinToString(", "),
            )
            .build()

    /**
     * Expressions for every scalar in [struct], read from the struct value [root], in the order
     * flatc's `create<Struct>` takes them. flatc's accessors for nested structs are nullable but
     * always present.
     */
    private fun flatten(struct: StructDef, root: String, structs: Map<FbsTypeName, StructDef>): List<String> =
        struct.fields.flatMap { field ->
            val access = "$root.${FlatcNames.accessor(field.name)}"
            when (val kind = field.kind) {
                is FieldKind.Struct -> flatten(structs.getValue(kind.type), "$access!!", structs)
                else -> listOf(access)
            }
        }
}
