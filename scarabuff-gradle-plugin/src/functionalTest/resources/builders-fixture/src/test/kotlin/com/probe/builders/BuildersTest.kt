package com.probe.builders

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.flatbuffers.FlatBufferBuilder
import com.probe.common.PointSpec
import com.probe.common.SharedSpec
import java.nio.ByteBuffer
import org.junit.jupiter.api.Test

/** Round-trips every field kind through the generated builders and flatc's own accessors. */
@OptIn(ExperimentalUnsignedTypes::class)
class BuildersTest {

    @Test
    fun `defaults come from the schema`() {
        val all = All.build()

        assertThat(all.aBool).isTrue()
        assertThat(all.aByte).isEqualTo(-2)
        assertThat(all.aUbyte).isEqualTo(7.toUByte())
        assertThat(all.aShort).isEqualTo(0)
        assertThat(all.aInt).isEqualTo(42)
        assertThat(all.aUint).isEqualTo(9u)
        assertThat(all.aLong).isEqualTo(-5L)
        assertThat(all.aUlong).isEqualTo(6uL)
        assertThat(all.aFloat).isEqualTo(1.5f)
        assertThat(all.aDouble).isEqualTo(2.25)
        assertThat(all.color).isEqualTo(Color.Green)
        assertThat(all.level).isEqualTo(Level.High)
        assertThat(all.nanFloat.isNaN()).isTrue()
        assertThat(all.infDouble).isEqualTo(Double.NEGATIVE_INFINITY)
        assertThat(all.someText).isNull()
        assertThat(all.inner).isNull()
        assertThat(all.vIntLength).isEqualTo(0)
        assertThat(all.optInt).isNull()
    }

    @Test
    fun `every field kind round-trips`() {
        val all = All.build(
            aBool = false, aByte = 1, aUbyte = 200u, aShort = -300, aUshort = 60000u,
            aInt = -7, aUint = 4000000000u, aLong = Long.MIN_VALUE, aUlong = 1uL,
            aFloat = 0.25f, aDouble = -1e300, color = Color.Red, level = Level.Low,
            someText = "hello",
            inner = InnerSpec(v = 5, label = "nested"),
            otherNs = SharedSpec(note = "other namespace"),
            vBool = listOf(true, false), vByte = listOf(-1, 2), vUbyte = listOf(255u, 0u),
            vShort = listOf(-2, 3), vUshort = listOf(65535u), vInt = listOf(1, 2, 3), vUint = listOf(7u),
            vLong = listOf(Long.MAX_VALUE), vUlong = listOf(ULong.MAX_VALUE), vFloat = listOf(1.5f),
            vDouble = listOf(2.5, 3.5), vColor = listOf(Color.Green, Color.Red),
            vString = listOf("a", "b"), vInner = listOf(InnerSpec(v = 1), InnerSpec(v = 2)),
            optInt = 0,
            object_ = 11, when_ = listOf("keyword"),
        )

        assertThat(all.aBool).isFalse()
        assertThat(all.aUbyte).isEqualTo(200.toUByte())
        assertThat(all.aUshort).isEqualTo(60000.toUShort())
        assertThat(all.aUint).isEqualTo(4000000000u)
        assertThat(all.aLong).isEqualTo(Long.MIN_VALUE)
        assertThat(all.aDouble).isEqualTo(-1e300)
        assertThat(all.color).isEqualTo(Color.Red)
        assertThat(all.someText).isEqualTo("hello")
        assertThat(all.inner!!.v).isEqualTo(5)
        assertThat(all.inner!!.label).isEqualTo("nested")
        assertThat(all.otherNs!!.note).isEqualTo("other namespace")
        assertThat(List(all.vBoolLength) { all.vBool(it) }).containsExactly(true, false).inOrder()
        assertThat(List(all.vUbyteLength) { all.vUbyte(it) }).containsExactly(255.toUByte(), 0.toUByte()).inOrder()
        assertThat(List(all.vIntLength) { all.vInt(it) }).containsExactly(1, 2, 3).inOrder()
        assertThat(all.vUlong(0)).isEqualTo(ULong.MAX_VALUE)
        assertThat(List(all.vColorLength) { all.vColor(it) }).containsExactly(Color.Green, Color.Red).inOrder()
        assertThat(List(all.vStringLength) { all.vString(it) }).containsExactly("a", "b").inOrder()
        assertThat(List(all.vInnerLength) { all.vInner(it)!!.v }).containsExactly(1, 2).inOrder()
        // An optional scalar set to its type's zero is still present, unlike a defaulted one.
        assertThat(all.optInt).isEqualTo(0)
        assertThat(all.object_).isEqualTo(11)
        assertThat(all.when_(0)).isEqualTo("keyword")
    }

    @Test
    fun `bytes round-trip`() {
        val bytes = All.buildBytes(aInt = 99, someText = "bytes")

        val all = All.fromBytes(bytes)

        assertThat(all.aInt).isEqualTo(99)
        assertThat(all.someText).isEqualTo("bytes")
    }

    @Test
    fun `copyInto re-serializes a received message`() {
        val original = All.fromBytes(All.buildBytes(someText = "copy", vInt = listOf(4, 5), inner = InnerSpec(v = 3)))

        val builder = FlatBufferBuilder()
        builder.finish(original.copyInto(builder))
        val copy = All.getRootAsAll(builder.dataBuffer())

        assertThat(copy.someText).isEqualTo("copy")
        assertThat(List(copy.vIntLength) { copy.vInt(it) }).containsExactly(4, 5).inOrder()
        assertThat(copy.inner!!.v).isEqualTo(3)
        assertThat(copy.aInt).isEqualTo(42)
    }

    @Test
    fun `required fields are parameters without defaults, and the root gets its file identifier`() {
        val bytes = Strict.buildBytes(name = "strict", inner = InnerSpec(v = 1), ids = listOf(9))

        assertThat(Strict.StrictBufferHasIdentifier(ByteBuffer.wrap(bytes))).isTrue()
        val strict = Strict.fromBytes(bytes)
        assertThat(strict.name).isEqualTo("strict")
        assertThat(strict.ids(0)).isEqualTo(9)

        val builder = FlatBufferBuilder()
        builder.finish(strict.copyInto(builder))
        assertThat(Strict.getRootAsStrict(builder.dataBuffer()).name).isEqualTo("strict")
    }

    @Test
    fun `structs build standalone and round-trip inside tables`() {
        val origin = Vec2Spec(x = 1.5f, y = -2f)
        assertThat(origin.x).isEqualTo(1.5f)
        assertThat(origin.y).isEqualTo(-2f)

        val shapes = Shapes.build(
            origin = origin,
            pose = PoseSpec(position = Vec2Spec(x = 3f, y = 4f), angle = 0.5, color = Color.Green, visible = true),
            path = listOf(Vec2Spec(0f, 0f), Vec2Spec(1f, 1f), Vec2Spec(2f, 4f)),
            otherNs = PointSpec(x = 7, y = 8),
            bounds = BoundsSpec(in_ = 10, is_ = 20),
        )

        assertThat(shapes.origin!!.x).isEqualTo(1.5f)
        assertThat(shapes.pose.position!!.y).isEqualTo(4f)
        assertThat(shapes.pose.angle).isEqualTo(0.5)
        assertThat(shapes.pose.color).isEqualTo(Color.Green)
        assertThat(shapes.pose.visible).isTrue()
        assertThat(List(shapes.pathLength) { shapes.path(it)!!.y }).containsExactly(0f, 1f, 4f).inOrder()
        assertThat(shapes.otherNs!!.y).isEqualTo(8)
        assertThat(shapes.bounds!!.in_).isEqualTo(10)
        assertThat(shapes.bounds!!.is_).isEqualTo(20)
    }

    @Test
    fun `optional struct fields stay absent, and copyInto keeps structs`() {
        val minimal = Shapes.build(pose = PoseSpec(Vec2Spec(0f, 0f), angle = 1.0, color = Color.Red, visible = false))
        assertThat(minimal.origin).isNull()
        assertThat(minimal.pathLength).isEqualTo(0)

        val original = Shapes.fromBytes(
            Shapes.buildBytes(
                pose = PoseSpec(Vec2Spec(5f, 6f), angle = 2.0, color = Color.Green, visible = true),
                path = listOf(Vec2Spec(9f, 9f)),
            )
        )
        val builder = FlatBufferBuilder()
        builder.finish(original.copyInto(builder))
        val copy = Shapes.getRootAsShapes(builder.dataBuffer())

        assertThat(copy.pose.position!!.x).isEqualTo(5f)
        assertThat(copy.pose.visible).isTrue()
        assertThat(copy.path(0)!!.x).isEqualTo(9f)
        assertThat(copy.origin).isNull()
    }

    @Test
    fun `the 0_4_0 bug report's struct vector schema compiles and round-trips`() {
        val payload = NumPayload.build(
            id = 9L,
            items = listOf(NumItemSpec(id = 1L, score = 0.5, active = true), NumItemSpec(id = 2L, score = 1.5, active = false)),
        )

        assertThat(List(payload.itemsLength) { payload.items(it)!!.id }).containsExactly(1L, 2L).inOrder()
        assertThat(payload.items(1)!!.active).isFalse()

        val long = AVeryLongTableNameThatPushesGeneratedLinesPastTheWrapLimit.build(
            aRatherLongVectorOfStructsFieldName = listOf(NumItemSpec(3L, 2.5, true)),
            anotherRatherLongVectorOfStringsField = listOf("wrapped"),
            anOptionalScalarWithALongFieldName = 0,
        )
        assertThat(long.aRatherLongVectorOfStructsFieldName(0)!!.score).isEqualTo(2.5)
        assertThat(long.anotherRatherLongVectorOfStringsField(0)).isEqualTo("wrapped")
        assertThat(long.anOptionalScalarWithALongFieldName).isEqualTo(0)
    }

    @Test
    fun `a struct Vector writes the same bytes as a list of specs, and reads as one`() {
        val list = List(100) { NumItemSpec(it.toLong(), it * 0.5, it % 3 == 0) }
        val vector = NumItemSpec.Vector(100) { i -> put(i.toLong(), i * 0.5, i % 3 == 0) }

        assertThat(NumPayload.buildBytes(id = 1, items = vector)).isEqualTo(NumPayload.buildBytes(id = 1, items = list))
        assertThat(vector).isEqualTo(list)
        assertThat(NumPayloadSpec(id = 1, items = vector)).isEqualTo(NumPayloadSpec(id = 1, items = list))
        assertThat(NumPayloadSpec(id = 1, items = vector).build().toSpec()).isEqualTo(NumPayloadSpec(id = 1, items = list))
        assertThat(NumItemSpec.Vector(0) { error("never called") }).isEmpty()
    }

    @Test
    fun `a struct Vector flattens nested structs into put`() {
        val pose = PoseSpec(Vec2Spec(0f, 0f), 0.0, Color.Red, false)
        val list = List(5) { PoseSpec(Vec2Spec(it * 1f, -it * 1f), it * 0.25, if (it % 2 == 0) Color.Green else Color.Red, it > 2) }
        val vector = PoseSpec.Vector(5) { i ->
            put(positionX = i * 1f, positionY = -i * 1f, angle = i * 0.25, color = if (i % 2 == 0) Color.Green else Color.Red, visible = i > 2)
        }

        assertThat(Shapes.buildBytes(pose = pose, poses = vector)).isEqualTo(Shapes.buildBytes(pose = pose, poses = list))
        assertThat(Shapes.build(pose = pose, poses = vector).toSpec().poses).isEqualTo(list)
    }

    @Test
    fun `a struct Vector's fill must put exactly once per index`() {
        val twice = NumItemSpec.Vector(2) { i -> put(i.toLong(), 0.0, true); put(i.toLong(), 0.0, true) }
        val never = NumItemSpec.Vector(2) { }

        for (vector in listOf(twice, never)) {
            val error = runCatching { NumPayload.buildBytes(items = vector) }.exceptionOrNull()
            assertThat(error).isInstanceOf(IllegalStateException::class.java)
            assertThat(error).hasMessageThat().contains("index 1")
        }
        assertThat(runCatching { twice[0] }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(runCatching { never[5] }.exceptionOrNull()).isInstanceOf(IndexOutOfBoundsException::class.java)
    }

    @Test
    fun `writing a struct Vector allocates nothing per struct`() {
        val threads = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val scores = DoubleArray(1000) { it * 0.5 }
        val builder = FlatBufferBuilder(32 * 1024)
        fun encode(): Int = NumPayload.build(
            id = 1, items = NumItemSpec.Vector(scores.size) { i -> put(i.toLong(), scores[i], i % 2 == 0) }, builder = builder,
        ).itemsLength
        repeat(2_000) { encode() }

        val runs = 200
        val before = threads.currentThreadAllocatedBytes
        repeat(runs) { encode() }
        val perMessage = (threads.currentThreadAllocatedBytes - before) / runs

        // A spec per struct would be over 30 KB for 1000 of them; what's left is a few small objects.
        assertWithMessage("bytes allocated per 1000-struct message").that(perMessage).isLessThan(1024L)
    }

    @Test
    fun `specs are data classes that round-trip through toSpec`() {
        val spec = AllSpec(
            someText = "spec",
            inner = InnerSpec(v = 4, label = "in"),
            vInner = listOf(InnerSpec(v = 1)),
            vString = listOf("a"),
            optInt = 0,
        )

        val message = spec.build()
        assertThat(message.toSpec()).isEqualTo(spec)
        assertThat(All.fromBytes(spec.buildBytes()).toSpec()).isEqualTo(spec)
        assertThat(spec.copy(someText = "changed")).isNotEqualTo(spec)

        val shapes = ShapesSpec(pose = PoseSpec(Vec2Spec(1f, 2f), angle = 3.0, color = Color.Green, visible = true), path = listOf(Vec2Spec(4f, 5f)))
        assertThat(shapes.build().toSpec()).isEqualTo(shapes)
        assertThat(shapes.build().pose.toSpec()).isEqualTo(shapes.pose)
    }

    @Test
    fun `a whole message tree is written into one builder`() {
        val builder = FlatBufferBuilder()
        val offset = AllSpec(inner = InnerSpec(v = 7), vInner = List(3) { InnerSpec(v = it) }).writeTo(builder)
        builder.finish(offset)

        val all = All.getRootAsAll(builder.dataBuffer())
        assertThat(all.inner!!.v).isEqualTo(7)
        assertThat(List(all.vInnerLength) { all.vInner(it)!!.v }).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `empty tables get a plain spec class`() {
        assertThat(EmptySpec()).isEqualTo(EmptySpec())
        assertThat(EmptySpec().build().toSpec()).isEqualTo(EmptySpec())
        assertThat(Empty.fromBytes(Empty.buildBytes())).isNotNull()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `the deprecated struct build still works for one release`() {
        val pose = Pose.build(position = Vec2.build(x = 1f, y = 2f), angle = 0.5, color = Color.Red, visible = true)

        assertThat(pose.toSpec()).isEqualTo(PoseSpec(Vec2Spec(1f, 2f), 0.5, Color.Red, true))
    }

    @Test
    fun `a presized or reused builder can be passed in`() {
        val reused = FlatBufferBuilder(64)

        val first = All.buildBytes(someText = "first", vInt = listOf(1, 2), builder = reused)
        val second = AllSpec(someText = "second").buildBytes(builder = reused)
        assertThat(All.fromBytes(first).someText).isEqualTo("first")
        assertThat(All.fromBytes(first).vIntLength).isEqualTo(2)
        // Cleared between uses: nothing from the first message leaks into the second.
        assertThat(All.fromBytes(second).someText).isEqualTo("second")
        assertThat(All.fromBytes(second).vIntLength).isEqualTo(0)

        val presized = Strict.build(name = "sized", inner = InnerSpec(v = 1), ids = listOf(3), builder = FlatBufferBuilder(128))
        assertThat(presized.name).isEqualTo("sized")
        assertThat(Strict.StrictBufferHasIdentifier(presized.byteBuffer)).isTrue()
    }

    @Test
    fun `string fields compare without decoding`() {
        val ascii = All.build(someText = "hello")
        assertThat(ascii.someTextEquals("hello")).isTrue()
        assertThat(ascii.someTextEquals("hell")).isFalse()
        assertThat(ascii.someTextEquals("hello!")).isFalse()
        assertThat(ascii.someTextEquals("Hello")).isFalse()
        assertThat(ascii.someTextEquals(null)).isFalse()

        val unicode = All.build(someText = "héllo wörld 🌍")
        assertThat(unicode.someTextEquals("héllo wörld 🌍")).isTrue()
        assertThat(unicode.someTextEquals("héllo wörld 🌎")).isFalse()
        assertThat(unicode.someTextEquals("hello world")).isFalse()
        assertThat(unicode.someTextEquals("héllo")).isFalse()

        assertThat(All.build(someText = "").someTextEquals("")).isTrue()
        assertThat(All.build(someText = "").someTextEquals(null)).isFalse()

        // Absent: flatc's own someTextAsByteBuffer throws here; the helper treats it as null.
        val absent = All.build()
        assertThat(absent.someTextEquals(null)).isTrue()
        assertThat(absent.someTextEquals("")).isFalse()

        val strict = Strict.build(name = "required", inner = InnerSpec(v = 1), ids = listOf(1))
        assertThat(strict.nameEquals("required")).isTrue()
        assertThat(strict.nameEquals("other")).isFalse()
    }

    @Test
    fun `size estimates cover the encoded message without overshooting`() {
        val messages = listOf(
            AllSpec(),
            AllSpec(someText = "hello", vInt = List(100) { it }, vString = List(20) { "string-$it" }),
            AllSpec(inner = InnerSpec(v = 1, label = "nested"), vInner = List(50) { InnerSpec(v = it, label = "item-$it") }),
            NumPayloadSpec(id = 1, items = List(1000) { NumItemSpec(it.toLong(), it * 0.5, it % 2 == 0) }),
            ShapesSpec(pose = PoseSpec(Vec2Spec(1f, 2f), 0.5, Color.Green, true), path = List(200) { Vec2Spec(it * 1f, 0f) }),
        )
        for (spec in messages) {
            val (estimate, actual) = when (spec) {
                is AllSpec -> spec.sizeHint() to spec.buildBytes().size
                is NumPayloadSpec -> spec.sizeHint() to spec.buildBytes().size
                is ShapesSpec -> spec.sizeHint() to spec.buildBytes().size
                else -> error("unexpected")
            }
            // sizeHint leaves out the root's vtable and offset, a few dozen bytes added separately.
            assertWithMessage("estimate for $spec").that(estimate + 64).isAtLeast(actual)
            assertWithMessage("estimate for $spec").that(estimate).isAtMost(actual * 2 + 64)
        }
    }
}
