package com.scarabuff.core.codegen

import com.google.common.truth.Truth.assertThat
import com.scarabuff.core.fbs.FbsTypeName
import com.scarabuff.core.fbs.FieldKind
import com.scarabuff.core.fbs.ScalarType
import com.scarabuff.core.fbs.StructDef
import com.scarabuff.core.fbs.StructFieldDef
import org.junit.jupiter.api.Test

/**
 * The encoding rules the generated struct builders depend on. That the output compiles and
 * round-trips is covered by the plugin's functional tests (builders-fixture).
 */
class StructBuilderGeneratorTest {

    private val vec2 = StructDef(
        FbsTypeName("com.example", "Vec2"),
        listOf(StructFieldDef("x", FieldKind.Scalar(ScalarType.FLOAT)), StructFieldDef("y", FieldKind.Scalar(ScalarType.FLOAT))),
    )
    private val pose = StructDef(
        FbsTypeName("com.example", "Pose"),
        listOf(
            StructFieldDef("position", FieldKind.Struct(vec2.type)),
            StructFieldDef("is", FieldKind.Scalar(ScalarType.BOOL)),
        ),
    )
    private val structs = listOf(vec2, pose).associateBy { it.type }

    private fun generate(struct: StructDef) = StructBuilderGenerator.generate(struct, structs).toString()

    @Test
    fun `build takes every field as a required parameter`() {
        val code = generate(vec2)

        assertThat(code).contains("fun Vec2.Companion.build(x: Float, y: Float): Vec2")
        assertThat(code).contains("_builder.finish(Vec2.createVec2(_builder, x, y))")
    }

    @Test
    fun `nested structs are flattened in declaration order, as flatc's create function expects`() {
        val code = generate(pose)

        assertThat(code).contains("fun Pose.Companion.build(position: Vec2, is_: Boolean): Pose")
        assertThat(code).contains("Pose.createPose(_builder, position.x, position.y, is_)")
        // KotlinPoet may wrap copyInto's argument list, so check the arguments on their own.
        assertThat(code).contains("this.position!!.x, this.position!!.y, this.is_)")
    }

    @Test
    fun `nested structs from another namespace import that namespace's copyInto`() {
        val other = StructDef(
            FbsTypeName("com.other", "Wrapper"),
            listOf(StructFieldDef("inner", FieldKind.Struct(vec2.type))),
        )

        val code = StructBuilderGenerator.generate(other, structs + (other.type to other)).toString()

        assertThat(code).contains("import com.example.copyInto")
    }
}
