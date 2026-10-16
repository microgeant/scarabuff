package com.scarabuff.core.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec

/**
 * `<Name>Spec.Vector`, for structs and tables alike: a `List<Spec>` whose elements come from a
 * `fill` callback instead of objects. `fill` calls `put` with the element's values, which writes
 * them straight into the message, so a vector costs no object per element. Reading it as a list
 * builds specs on demand, so it still compares equal to an ordinary list of the same values.
 *
 * The writer's own members start with `_`, which no flatc field accessor does, so `put`'s
 * parameters can't shadow them. `Fill` is a `fun interface` with an extension method, so the
 * lambda gets `put` in scope and its `Int` index isn't boxed.
 */
internal object SpecVectorGenerator {

    private val FLAT_BUFFER_BUILDER = ClassName("com.google.flatbuffers", "FlatBufferBuilder")
    private val ABSTRACT_LIST = ClassName("kotlin.collections", "AbstractList")

    /**
     * @param kind `struct` or `message`, for the docs
     * @param example `put`'s arguments in the KDoc example
     * @param order the order writing calls `fill` in, for the docs
     * @param write writes the element into `_builder` (non-null there) and returns its offset
     * @param record the element's spec, from `put`'s parameters
     * @param companion functions the table builders call, given the Vector and Writer classes
     */
    fun generate(
        spec: ClassName,
        typeName: String,
        kind: String,
        putParameters: List<ParameterSpec>,
        putKdoc: String,
        example: String,
        order: String,
        write: CodeBlock,
        record: CodeBlock,
        companion: (vector: ClassName, writer: ClassName) -> List<FunSpec>,
    ): TypeSpec {
        val vector = spec.nestedClass("Vector")
        val writer = vector.nestedClass("Writer")
        val fill = vector.nestedClass("Fill")
        return TypeSpec.classBuilder(vector)
            .addKdoc(
                "%L %Ls for a vector field, produced by [fill] while the message is written, so there's no " +
                    "object per %L: `%L.Vector(n) { i -> put(%L) }`. Table specs take it wherever they take a " +
                    "`List<%L>`.\n\n" +
                    "[fill] must call `put` exactly once per index, with the same values every time: writing calls " +
                    "it %L, and reading an element of the list calls it again.",
                typeName, kind, kind, spec.simpleName, example, spec.simpleName, order,
            )
            .superclass(ABSTRACT_LIST.parameterizedBy(spec))
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("size", INT)
                    .addParameter("fill", fill)
                    .build()
            )
            .addProperty(PropertySpec.builder("size", INT, KModifier.OVERRIDE).initializer("size").build())
            .addProperty(PropertySpec.builder("fill", fill, KModifier.PRIVATE).initializer("fill").build())
            .addInitializerBlock(CodeBlock.of("require(size >= 0) { \"size must not be negative: \$size\" }\n"))
            .addType(
                TypeSpec.funInterfaceBuilder(fill)
                    .addKdoc("Calls [Writer.put] once with the %L at [index].", kind)
                    .addFunction(
                        FunSpec.builder("write")
                            .addModifiers(KModifier.ABSTRACT)
                            .receiver(writer)
                            .addParameter("index", INT)
                            .build()
                    )
                    .build()
            )
            .addType(writerType(spec, writer, kind, putParameters, putKdoc, write, record))
            .addFunction(
                FunSpec.builder("get")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("index", INT)
                    .returns(spec)
                    .addStatement("if (index !in 0 until size) throw IndexOutOfBoundsException(\"index \$index, size \$size\")")
                    .addStatement("val _writer = %T(null)", writer)
                    .addStatement("write(_writer, index)")
                    .addStatement("return _writer._last!!")
                    .build()
            )
            .addFunction(
                FunSpec.builder("write")
                    .addModifiers(KModifier.PRIVATE)
                    .addKdoc("Calls [fill] for [index], checking it called `put` once. Returns what `put` wrote.")
                    .addParameter("writer", writer)
                    .addParameter("index", INT)
                    .returns(INT)
                    .addStatement("writer._puts = 0")
                    .addStatement("with(fill)·{ writer.write(index) }")
                    .addStatement("check(writer._puts == 1) { \"fill called put \${writer._puts} times for index \$index, not once\" }")
                    .addStatement("return writer._offset")
                    .build()
            )
            .addType(
                TypeSpec.companionObjectBuilder()
                    .addModifiers(KModifier.INTERNAL)
                    .addFunctions(companion(vector, writer))
                    .build()
            )
            .build()
    }

    private fun writerType(
        spec: ClassName,
        writer: ClassName,
        kind: String,
        putParameters: List<ParameterSpec>,
        putKdoc: String,
        write: CodeBlock,
        record: CodeBlock,
    ): TypeSpec {
        val put = FunSpec.builder("put")
            .addKdoc(putKdoc)
            .addParameters(putParameters)
            .addStatement("_puts++")
            .beginControlFlow("if (_builder != null)")
            .addStatement("_offset = %L", write)
            .nextControlFlow("else")
            .addStatement("_last = %L", record)
            .endControlFlow()
        return TypeSpec.classBuilder(writer)
            .addKdoc("Receives the %L for each index, from [Fill].", kind)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("_builder", FLAT_BUFFER_BUILDER.copy(nullable = true))
                    .build()
            )
            .addProperty(
                PropertySpec.builder("_builder", FLAT_BUFFER_BUILDER.copy(nullable = true), KModifier.PRIVATE)
                    .initializer("_builder").build()
            )
            .addProperty(PropertySpec.builder("_puts", INT, KModifier.INTERNAL).mutable().initializer("0").build())
            .addProperty(PropertySpec.builder("_offset", INT, KModifier.INTERNAL).mutable().initializer("0").build())
            .addProperty(
                PropertySpec.builder("_last", spec.copy(nullable = true), KModifier.INTERNAL)
                    .mutable().initializer("null").build()
            )
            .addFunction(put.build())
            .build()
    }
}
