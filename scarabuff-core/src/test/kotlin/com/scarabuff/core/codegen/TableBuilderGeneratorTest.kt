package com.scarabuff.core.codegen

import com.google.common.truth.Truth.assertThat
import com.scarabuff.core.fbs.FbsTypeName
import com.scarabuff.core.fbs.FieldDef
import com.scarabuff.core.fbs.FieldKind
import com.scarabuff.core.fbs.ScalarType
import com.scarabuff.core.fbs.TableDef
import org.junit.jupiter.api.Test

/**
 * The naming and encoding rules the generated builders depend on. That the output compiles and
 * round-trips every field kind is covered by the plugin's functional tests (builders-fixture).
 */
class TableBuilderGeneratorTest {

    private val pkg = "com.example"

    private fun field(
        name: String,
        kind: FieldKind,
        id: Int = 0,
        required: Boolean = false,
        optionalScalar: Boolean = false,
        defaultValue: String? = if (kind is FieldKind.Scalar && !optionalScalar) "0" else null,
        defaultEnumValue: String? = null,
    ) = FieldDef(name, id, kind, required, optionalScalar, defaultValue, defaultEnumValue)

    private fun table(
        vararg fields: FieldDef,
        fileIdentifier: String? = null,
        unsupportedReason: String? = null,
    ) = TableDef(FbsTypeName(pkg, "Msg"), fields.toList(), fileIdentifier, unsupportedReason)

    private fun generate(table: TableDef) = TableBuilderGenerator.generate(table).toString()

    @Test
    fun `snake_case fields use flatc's camelCase names`() {
        val code = generate(table(field("created_at", FieldKind.Scalar(ScalarType.LONG))))

        assertThat(code).contains("createdAt: Long = 0")
        assertThat(code).contains("Msg.addCreatedAt(builder, createdAt)")
        assertThat(code).contains("createdAt = this.createdAt")
    }

    @Test
    fun `keyword fields get flatc's underscore, except in the vector create function`() {
        val code = generate(
            table(
                field("object", FieldKind.Scalar(ScalarType.INT)),
                field("when", FieldKind.StrVector),
            )
        )

        assertThat(code).contains("Msg.addObject_(builder, object_)")
        assertThat(code).contains("Msg.createWhenVector(builder,")
        assertThat(code).contains("Msg.addWhen_(builder, it)")
        assertThat(code).contains("this.when_Length")
    }

    @Test
    fun `required fields are non-null parameters without defaults`() {
        val code = generate(table(field("name", FieldKind.Str, required = true)))

        assertThat(code).contains("fun Msg.Companion.build(name: String): Msg")
        assertThat(code).contains("val o_name = name.let { builder.createString(it) }")
        assertThat(code).contains("Msg.addName(builder, o_name)")
    }

    @Test
    fun `optional scalars are written by slot so a zero value isn't dropped`() {
        val code = generate(table(field("count", FieldKind.Scalar(ScalarType.UINT), id = 7, optionalScalar = true)))

        assertThat(code).contains("count: UInt? = null")
        assertThat(code).contains("count?.let { builder.addInt(it.toInt()); builder.slot(7) }")
    }

    @Test
    fun `enum defaults use the enum constant`() {
        val color = FbsTypeName(pkg, "Color")
        val code = generate(
            table(field("color", FieldKind.Scalar(ScalarType.BYTE, color), defaultValue = "3", defaultEnumValue = "Green"))
        )

        assertThat(code).contains("color: Byte = Color.Green")
    }

    @Test
    fun `nested tables from another namespace import that namespace's copyInto`() {
        val code = generate(table(field("author", FieldKind.Table(FbsTypeName("com.other", "User")))))

        assertThat(code).contains("import com.other.copyInto")
        assertThat(code).contains("val o_author = author?.let { it.copyInto(builder) }")
    }

    @Test
    fun `unsigned vectors opt in to the experimental unsigned array API`() {
        val code = generate(table(field("bytes", FieldKind.ScalarVector(FieldKind.Scalar(ScalarType.UBYTE)))))

        assertThat(code).contains("@file:OptIn(ExperimentalUnsignedTypes::class)")
        assertThat(code).contains("Msg.createBytesVector(builder, it.toUByteArray())")
    }

    @Test
    fun `the root table is finished with its file identifier`() {
        val code = generate(table(field("x", FieldKind.Scalar(ScalarType.INT)), fileIdentifier = "MSG1"))

        assertThat(code).contains("builder.finish(Msg.pack(builder, x = x), \"MSG1\")")
    }

    @Test
    fun `unsupported tables only get fromBytes`() {
        val code = generate(table(unsupportedReason = "union field `payload`"))

        assertThat(code).contains("fun Msg.Companion.fromBytes(bytes: ByteArray): Msg")
        assertThat(code).doesNotContain("fun Msg.Companion.build(")
        assertThat(code).doesNotContain("copyInto")
    }

    @Test
    fun `struct fields are written inline, right before their add call`() {
        val vec2 = FbsTypeName(pkg, "Vec2")
        val code = generate(
            table(
                field("origin", FieldKind.Struct(vec2)),
                field("pose", FieldKind.Struct(FbsTypeName(pkg, "Pose")), required = true),
            )
        )

        assertThat(code).contains("origin: Vec2? = null")
        assertThat(code).contains("pose: Pose,")
        assertThat(code).contains("origin?.let { Msg.addOrigin(builder, it.copyInto(builder)) }")
        assertThat(code).contains("Msg.addPose(builder, pose.copyInto(builder))")
        // Nothing for structs is written before the table starts.
        assertThat(code.substringBefore("Msg.startMsg(builder)")).doesNotContain("copyInto")
    }

    @Test
    fun `vectors of structs are written last-first between start and end vector`() {
        val code = generate(table(field("path", FieldKind.StructVector(FbsTypeName(pkg, "Vec2")))))

        assertThat(code).contains(
            "Msg.startPathVector(builder, it.size); it.asReversed().forEach { s ->"
        )
        assertThat(code).contains("builder.endVector()")
        assertThat(code).contains("List(this.pathLength) { this.path(it)!! }")
    }
}
