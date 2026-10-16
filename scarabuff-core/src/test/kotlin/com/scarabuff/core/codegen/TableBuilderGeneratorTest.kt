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

    /** The generated code with all whitespace runs collapsed, so assertions don't depend on line wrapping. */
    private fun generateFlat(table: TableDef) = generate(table).replace(Regex("""\s+"""), " ")

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
        val code = generateFlat(table(field("name", FieldKind.Str, required = true)))

        assertThat(code).contains("fun Msg.Companion.build(name: String, builder: FlatBufferBuilder = FlatBufferBuilder(Msg.sizeHint(name = name) + 18)): Msg")
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
    fun `nested tables are specs, written into the same builder`() {
        val code = generate(table(field("author", FieldKind.Table(FbsTypeName("com.other", "User")))))

        assertThat(code).contains("author: UserSpec? = null")
        assertThat(code).contains("import com.other.UserSpec")
        assertThat(code).contains("val o_author = author?.let { it.writeTo(builder) }")
        // Reading a received message back into a spec uses the nested table's toSpec.
        assertThat(code).contains("import com.other.toSpec")
        assertThat(code).contains("author = this.author?.toSpec()")
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

        assertThat(code).contains("origin: Vec2Spec? = null")
        assertThat(code).contains("pose: PoseSpec,")
        assertThat(code).contains("origin?.let { Msg.addOrigin(builder, it.writeTo(builder)) }")
        assertThat(code).contains("Msg.addPose(builder, pose.writeTo(builder))")
        // Nothing for structs is written before the table starts.
        val pack = code.substringAfter("fun Msg.Companion.pack(")
        assertThat(pack.substringBefore("Msg.startMsg(builder)")).doesNotContain("writeTo")
    }

    @Test
    fun `vectors of structs are written last-first between start and end vector`() {
        val code = generateFlat(table(field("path", FieldKind.StructVector(FbsTypeName(pkg, "Vec2")))))

        assertThat(code).contains("path: List<Vec2Spec>? = null")
        assertThat(code).contains("Msg.startPathVector(builder, it.size);")
        assertThat(code).contains("it.asReversed().forEach { s -> s.writeTo(builder) }")
        assertThat(code).contains("builder.endVector()")
        assertThat(code).contains("List(this.pathLength) { this.path(it)!!.toSpec() }")
    }

    @Test
    fun `every table gets a spec data class mirroring its fields`() {
        val code = generateFlat(
            table(
                field("name", FieldKind.Str, required = true),
                field("count", FieldKind.Scalar(ScalarType.INT), defaultValue = "3"),
            )
        )

        assertThat(code).contains("public data class MsgSpec( public val name: String, public val count: Int = 3, )")
        assertThat(code).contains("public fun writeTo(builder: FlatBufferBuilder): Int = Msg.pack(builder, name = name, count = count)")
        assertThat(code).contains("public fun Msg.toSpec(): MsgSpec")
        assertThat(code).contains("public fun Msg.copyInto(builder: FlatBufferBuilder): Int = toSpec().writeTo(builder)")
    }

    @Test
    fun `a table without fields gets a plain spec class`() {
        val code = generate(table())

        assertThat(code).contains("public class MsgSpec")
        assertThat(code).doesNotContain("data class")
        assertThat(code).contains("override fun equals(other: Any?): Boolean = other is MsgSpec")
        assertThat(code).contains("Msg.pack(builder)")
    }

    @Test
    fun `build and buildBytes take an optional builder, cleared first`() {
        val code = generateFlat(table(field("x", FieldKind.Scalar(ScalarType.INT))))

        assertThat(code).contains(
            "fun Msg.Companion.buildBytes(x: Int = 0, builder: FlatBufferBuilder = FlatBufferBuilder(Msg.sizeHint() + 18)): ByteArray { builder.clear()"
        )
        assertThat(code).contains("fun Msg.Companion.build(x: Int = 0, builder: FlatBufferBuilder = FlatBufferBuilder(Msg.sizeHint() + 18)): Msg { builder.clear()")
        // The spec forwards its builder.
        assertThat(code).contains("public fun buildBytes(builder: FlatBufferBuilder = FlatBufferBuilder(sizeHint() + 18)): ByteArray = Msg.buildBytes(x = x, builder = builder)")
    }

    @Test
    fun `string fields get an equals helper that doesn't decode`() {
        val access = FieldAccessGenerator.className(listOf(pkg))
        val code = TableBuilderGenerator.generate(
            table(
                field("title", FieldKind.Str, id = 2),
                field("name", FieldKind.Str, id = 3, required = true),
            ),
            access,
        ).toString().replace(Regex("""\s+"""), " ")

        // The comparison runs in the access object, straight on the buffer; vtable slot = 4 + 2 * id.
        assertThat(code).contains("public fun Msg.titleEquals(`value`: String?): Boolean = ${access.simpleName}.stringEquals(this, 8, value)")
        assertThat(code).contains("public fun Msg.nameEquals(`value`: String?): Boolean = ${access.simpleName}.stringEquals(this, 10, value)")
        // No slice, and no per-file helper any more.
        assertThat(code).doesNotContain("AsByteBuffer")
        assertThat(code).doesNotContain("scarabuffUtf8Equals")
    }

    @Test
    fun `the field access object lives in flatbuffers' package and is internal`() {
        val access = FieldAccessGenerator.className(listOf("com.b", "com.a"))
        val code = FieldAccessGenerator.generate(access).toString()

        assertThat(access.packageName).isEqualTo("com.google.flatbuffers")
        assertThat(access).isEqualTo(FieldAccessGenerator.className(listOf("com.a", "com.b")))
        assertThat(access).isNotEqualTo(FieldAccessGenerator.className(listOf("com.c")))
        assertThat(code).contains("internal object ${access.simpleName}")
        assertThat(code).contains("table.__offset(vtableOffset) != 0")
        // String equality reads the buffer directly, through Table's package-visible members.
        val flat = code.replace(Regex("""\s+"""), " ")
        assertThat(flat).contains("public fun stringEquals( table: Table, vtableOffset: Int, `value`: String?, ): Boolean {")
        assertThat(flat).contains("val start = table.__vector(o) val length = table.__vector_len(o)")
    }

    @Test
    fun `builders start at an estimate of the message size, not a flat 1 KB`() {
        val vec2 = FbsTypeName(pkg, "Vec2")
        val item = FbsTypeName(pkg, "Item")
        val itemTable = TableDef(item, listOf(field("name", FieldKind.Str, id = 0)), null, null)
        val code = TableBuilderGenerator.generate(
            table(
                field("id", FieldKind.Scalar(ScalarType.LONG), id = 0),
                field("title", FieldKind.Str, id = 1),
                field("path", FieldKind.StructVector(vec2), id = 2),
                field("items", FieldKind.TableVector(item), id = 3, required = true),
            ),
            tables = mapOf(item to itemTable),
            structs = mapOf(vec2 to com.scarabuff.core.fbs.StructDef(vec2, emptyList(), byteSize = 8)),
        ).toString().replace(Regex("""\s+"""), " ")

        assertThat(code).contains("internal fun Msg.Companion.sizeHint( title: String?, path: List<Vec2Spec>?, items: List<ItemSpec>, ): Int {")
        // Fixed part: vtable offset 4 + alignment 8 + the long 8 + three 4-byte offsets.
        assertThat(code).contains("var _size = 32")
        assertThat(code).contains("if (title != null) { _size += 8 + title.length }")
        // Struct vectors use the struct's exact size; no per-item work.
        assertThat(code).contains("if (path != null) { _size += 8 + 8 * path.size }")
        // Table vectors: the first element's estimate times the count, plus the element's vtable once.
        assertThat(code).contains("_size += 8 + 4 * items.size if (items.isNotEmpty()) { _size += items.size * items[0].sizeHint() + 6 }")
        assertThat(code).contains("internal fun sizeHint(): Int = Msg.sizeHint(title = title, path = path, items = items)")
        // Root overhead: this table's vtable (4 + 2 * 4 slots), root offset and alignment.
        assertThat(code).contains("FlatBufferBuilder(Msg.sizeHint(title = title, path = path, items = items) + 24)")
    }
}
