package com.probe.builders

import com.google.common.truth.Truth.assertThat
import com.google.flatbuffers.FlatBufferBuilder
import com.probe.common.Point
import com.probe.common.Shared
import com.probe.common.build
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
            inner = Inner.build(v = 5, label = "nested"),
            otherNs = Shared.build(note = "other namespace"),
            vBool = listOf(true, false), vByte = listOf(-1, 2), vUbyte = listOf(255u, 0u),
            vShort = listOf(-2, 3), vUshort = listOf(65535u), vInt = listOf(1, 2, 3), vUint = listOf(7u),
            vLong = listOf(Long.MAX_VALUE), vUlong = listOf(ULong.MAX_VALUE), vFloat = listOf(1.5f),
            vDouble = listOf(2.5, 3.5), vColor = listOf(Color.Green, Color.Red),
            vString = listOf("a", "b"), vInner = listOf(Inner.build(v = 1), Inner.build(v = 2)),
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
        val original = All.fromBytes(All.buildBytes(someText = "copy", vInt = listOf(4, 5), inner = Inner.build(v = 3)))

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
        val bytes = Strict.buildBytes(name = "strict", inner = Inner.build(v = 1), ids = listOf(9))

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
        val origin = Vec2.build(x = 1.5f, y = -2f)
        assertThat(origin.x).isEqualTo(1.5f)
        assertThat(origin.y).isEqualTo(-2f)

        val shapes = Shapes.build(
            origin = origin,
            pose = Pose.build(position = Vec2.build(x = 3f, y = 4f), angle = 0.5, color = Color.Green, visible = true),
            path = listOf(Vec2.build(0f, 0f), Vec2.build(1f, 1f), Vec2.build(2f, 4f)),
            otherNs = Point.build(x = 7, y = 8),
            bounds = Bounds.build(in_ = 10, is_ = 20),
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
        val minimal = Shapes.build(pose = Pose.build(Vec2.build(0f, 0f), angle = 1.0, color = Color.Red, visible = false))
        assertThat(minimal.origin).isNull()
        assertThat(minimal.pathLength).isEqualTo(0)

        val original = Shapes.fromBytes(
            Shapes.buildBytes(
                pose = Pose.build(Vec2.build(5f, 6f), angle = 2.0, color = Color.Green, visible = true),
                path = listOf(Vec2.build(9f, 9f)),
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
}
