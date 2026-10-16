package com.scarabuff.core.codegen

import com.scarabuff.core.fbs.FbsTypeName
import com.scarabuff.core.fbs.FieldKind
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.U_BYTE

/**
 * Generates `<Union>Spec.kt` for a union of tables: an interface that each member's spec
 * implements, so a union field takes `InnerSpec(...)` directly, like any nested table.
 *
 * flatc's Kotlin union is a type tag (a `UByte` constant per member, in flatc's `<Union>` class)
 * plus an untyped table offset. The interface's companion maps a spec back to its tag. It can't be
 * a `sealed` interface: members may live in other packages, which Kotlin doesn't allow for sealed
 * types, so the `when` needs an `else` for specs implemented elsewhere.
 */
object UnionGenerator {

    private val FLAT_BUFFER_BUILDER = ClassName("com.google.flatbuffers", "FlatBufferBuilder")

    /** The generated interface for a union: `Payload` gets `PayloadSpec`, like a table. */
    fun specClassName(union: FieldKind.Union) = TableBuilderGenerator.specClassName(union.type)

    /**
     * @param vtableSize the size of each member's vtable, for [sizeHint]; see TableBuilderGenerator
     */
    fun generate(union: FieldKind.Union, vtableSize: (FbsTypeName) -> Int): FileSpec {
        val spec = specClassName(union)
        val unionClass = union.type.toClassName()
        val typeOf = FunSpec.builder("typeOf")
            .addModifiers(KModifier.INTERNAL)
            .addKdoc("The union's type tag for [spec].")
            .addParameter("spec", spec)
            .returns(U_BYTE)
            .beginControlFlow("return when (spec)")
            .apply {
                for (member in union.members) {
                    addStatement("is %T -> %T.%L", TableBuilderGenerator.specClassName(member.table), unionClass, member.name)
                }
            }
            .addStatement("else -> throw IllegalArgumentException(%P)", "\${spec::class} isn't a ${unionClass.simpleName} member")
            .endControlFlow()
            .build()
        val sizeHint = FunSpec.builder("sizeHint")
            .addModifiers(KModifier.INTERNAL)
            .addKdoc("An estimate of [spec]'s encoded size, its vtable included, to size builders.")
            .addParameter("spec", spec)
            .returns(INT)
            .beginControlFlow("return when (spec)")
            .apply {
                for (member in union.members) {
                    val memberSpec = TableBuilderGenerator.specClassName(member.table)
                    addStatement("is %T -> spec.sizeHint() + %L", memberSpec, vtableSize(member.table))
                }
            }
            .addStatement("else -> 64")
            .endControlFlow()
            .build()
        return FileSpec.builder(spec.packageName, spec.simpleName)
            .addType(
                TypeSpec.interfaceBuilder(spec)
                    .addKdoc(
                        "A %T union value: the spec of one of its member tables (%L).",
                        unionClass,
                        union.members.joinToString(", ") { TableBuilderGenerator.specClassName(it.table).simpleName },
                    )
                    .addFunction(
                        FunSpec.builder("writeTo")
                            .addModifiers(KModifier.ABSTRACT)
                            .addKdoc("Writes this message into [builder] and returns its offset.")
                            .addParameter("builder", FLAT_BUFFER_BUILDER)
                            .returns(INT)
                            .build()
                    )
                    .addType(
                        // Interfaces can't have internal members, so the companion is public and
                        // its functions internal.
                        TypeSpec.companionObjectBuilder()
                            .addFunction(typeOf)
                            .addFunction(sizeHint)
                            .build()
                    )
                    .build()
            )
            .build()
    }
}
