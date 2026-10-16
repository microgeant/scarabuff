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
        byteSize = 8,
    )
    private val pose = StructDef(
        FbsTypeName("com.example", "Pose"),
        listOf(
            StructFieldDef("position", FieldKind.Struct(vec2.type)),
            StructFieldDef("is", FieldKind.Scalar(ScalarType.BOOL)),
        ),
        byteSize = 12,
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
    fun `the 0_4_0 build is gone, as 0_5_0 announced`() {
        assertThat(generate(pose)).doesNotContain("fun Pose.Companion.build(")
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
    fun `Vector is a list of specs whose put writes the struct without a spec`() {
        val code = generate(vec2).replace(Regex("""\s+"""), " ")

        assertThat(code).contains("public class Vector( public override val size: Int, private val fill: Fill, ) : AbstractList<Vec2Spec>()")
        assertThat(code).contains("public fun interface Fill {")
        assertThat(code).contains("public fun Writer.write(index: Int)")
        assertThat(code).contains("public fun put(x: Float, y: Float)")
        assertThat(code).contains("Vec2.createVec2(_builder, x, y)")
        assertThat(code).contains("_last = Vec2Spec(x, y)")
        // Specs are only made when the list is read; writing goes through one Writer.
        assertThat(code).contains("for (i in specs.size - 1 downTo 0) specs.write(_writer, i)")
        assertThat(code).contains("for (i in specs.size - 1 downTo 0) specs[i].writeTo(builder)")
    }

    @Test
    fun `Vector's put flattens nested structs, named by their path`() {
        val code = generate(pose).replace(Regex("""\s+"""), " ")

        assertThat(code).contains("public fun put( positionX: Float, positionY: Float, is_: Boolean, )")
        assertThat(code).contains("Pose.createPose(_builder, positionX, positionY, is_)")
        assertThat(code).contains("_last = PoseSpec(Vec2Spec(positionX, positionY), is_)")
    }

    @Test
    fun `Vector's flattened names fall back to underscores when camel case would clash`() {
        val clashing = StructDef(
            FbsTypeName("com.example", "Clash"),
            listOf(
                StructFieldDef("position", FieldKind.Struct(vec2.type)),
                StructFieldDef("position_x", FieldKind.Scalar(ScalarType.FLOAT)),
            ),
            byteSize = 12,
        )

        val code = StructBuilderGenerator.generate(clashing, structs + (clashing.type to clashing)).toString()
            .replace(Regex("""\s+"""), " ")

        assertThat(code).contains("public fun put( position_x: Float, position_y: Float, positionX: Float, )")
    }

    @Test
    fun `nested structs from another namespace import that namespace's toSpec`() {
        val other = StructDef(
            FbsTypeName("com.other", "Wrapper"),
            listOf(StructFieldDef("inner", FieldKind.Struct(vec2.type))),
            byteSize = 8,
        )

        val code = StructBuilderGenerator.generate(other, structs + (other.type to other)).toString()

        assertThat(code).contains("import com.example.toSpec")
        assertThat(code).contains("import com.example.Vec2Spec")
    }
}
