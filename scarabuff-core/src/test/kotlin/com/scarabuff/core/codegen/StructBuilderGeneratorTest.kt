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
    fun `the spec is a data class that writes the struct inline`() {
        val code = generate(vec2)

        assertThat(code).contains("public data class Vec2Spec(")
        assertThat(code).contains("public val x: Float,")
        assertThat(code).contains("public fun writeTo(builder: FlatBufferBuilder): Int = Vec2.createVec2(builder, x, y)")
        assertThat(code).contains("public fun Vec2.toSpec(): Vec2Spec = Vec2Spec(x = this.x, y = this.y)")
    }

    @Test
    fun `the 0_4_0 build is kept, deprecated, with its old signature`() {
        val code = generate(pose)

        assertThat(code).contains("@Deprecated(")
        assertThat(code).contains("fun Pose.Companion.build(position: Vec2, is_: Boolean): Pose")
        assertThat(code).contains("_builder.finish(Pose.createPose(_builder, position.x, position.y, is_))")
    }

    @Test
    fun `nested structs are flattened in declaration order, as flatc's create function expects`() {
        // Whitespace collapsed, so the assertions don't depend on where KotlinPoet wraps lines.
        val code = generate(pose).replace(Regex("""\s+"""), " ")

        assertThat(code).contains("public data class PoseSpec( public val position: Vec2Spec, public val is_: Boolean, )")
        assertThat(code).contains("Pose.createPose(builder, position.x, position.y, is_)")
        assertThat(code).contains("position = this.position!!.toSpec()")
        assertThat(code).contains("Pose.createPose(builder, this.position!!.x, this.position!!.y, this.is_)")
    }

    @Test
    fun `nested structs from another namespace import that namespace's toSpec`() {
        val other = StructDef(
            FbsTypeName("com.other", "Wrapper"),
            listOf(StructFieldDef("inner", FieldKind.Struct(vec2.type))),
        )

        val code = StructBuilderGenerator.generate(other, structs + (other.type to other)).toString()

        assertThat(code).contains("import com.example.toSpec")
        assertThat(code).contains("import com.example.Vec2Spec")
    }
}
