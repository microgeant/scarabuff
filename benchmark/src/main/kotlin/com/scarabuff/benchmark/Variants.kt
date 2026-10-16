package com.scarabuff.benchmark

import com.google.flatbuffers.FlatBufferBuilder
import com.scarabuff.benchmark.fb.Item as FbItem
import com.scarabuff.benchmark.fb.ItemSpec as FbItemSpec
import com.scarabuff.benchmark.fb.NumItem as FbNumItem
import com.scarabuff.benchmark.fb.NumItemSpec as FbNumItemSpec
import com.scarabuff.benchmark.fb.NumPayload as FbNumPayload
import com.scarabuff.benchmark.fb.Payload as FbPayload
import com.scarabuff.benchmark.fb.build as fbBuild
import com.scarabuff.benchmark.fb.buildBytes as fbBuildBytes
import com.scarabuff.benchmark.fb.pack as fbPack
import com.scarabuff.benchmark.fb.nameEquals as fbNameEquals
import com.scarabuff.benchmark.pb.NumItem as PbNumItem
import com.scarabuff.benchmark.pb.NumPayload as PbNumPayload
import com.scarabuff.benchmark.pb.Payload as PbPayload

/** Different ways to produce the same Payload bytes, to find where scarabuff's builders lose time. */
object EncodeVariants {
    private val reusable = ThreadLocal.withInitial { FlatBufferBuilder(1024) }

    private fun body(b: FlatBufferBuilder, src: Source, viaPack: Boolean): ByteArray {
        val items = IntArray(src.n) { i ->
            if (viaPack) FbItem.fbPack(b, i.toLong(), src.names[i], src.scores[i], i % 2 == 0)
            else FbItem.createItem(b, i.toLong(), b.createString(src.names[i]), src.scores[i], i % 2 == 0)
        }
        val vec = FbPayload.createItemsVector(b, items)
        b.finish(FbPayload.createPayload(b, 42L, b.createString("benchmark payload"), 1_700_000_000_000L, vec))
        return b.sizedByteArray()
    }

    /** Hand-written, flatc's default 1 KB initial builder (same as the generated `build`). */
    @JvmStatic fun handDefault(src: Source) = body(FlatBufferBuilder(), src, viaPack = false)

    /** Hand-written, builder pre-sized to roughly the final message (a caller who knows its sizes). */
    @JvmStatic fun handPresized(src: Source) = body(FlatBufferBuilder(src.n * 60 + 256), src, viaPack = false)

    /** Items written with the generated `Item.pack` straight into one shared builder; no per-item copy. */
    @JvmStatic fun packItemsShared(src: Source) = body(FlatBufferBuilder(), src, viaPack = true)

    /** Hand-written with a reused (thread-local, cleared) builder: no builder allocation per message. */
    @JvmStatic fun handReused(src: Source): ByteArray {
        val b = reusable.get()
        b.clear()
        return body(b, src, viaPack = false)
    }

    /** The shipped path: `Payload.buildBytes(items = List<ItemSpec>)`; specs are packed into one builder, sized from the values (0.5.1). */
    @JvmStatic fun generated(src: Source) = FbPayload.fbBuildBytes(
        id = 42L, title = "benchmark payload", timestampMs = 1_700_000_000_000L,
        items = List(src.n) { i -> FbItemSpec(id = i.toLong(), name = src.names[i], score = src.scores[i], active = i % 2 == 0) },
    )

    /** 0.6.0: `ItemSpec.Vector`, with no spec per item. */
    @JvmStatic fun generatedVector(src: Source) = FbPayload.fbBuildBytes(
        id = 42L, title = "benchmark payload", timestampMs = 1_700_000_000_000L,
        items = FbItemSpec.Vector(src.n) { i -> put(id = i.toLong(), name = src.names[i], score = src.scores[i], active = i % 2 == 0) },
    )

    /** The optional `builder` argument (0.5.0): the generated path with a reused thread-local builder. */
    @JvmStatic fun generatedReused(src: Source) = FbPayload.fbBuildBytes(
        id = 42L, title = "benchmark payload", timestampMs = 1_700_000_000_000L,
        items = List(src.n) { i -> FbItemSpec(id = i.toLong(), name = src.names[i], score = src.scores[i], active = i % 2 == 0) },
        builder = reusable.get(),
    )

    /** The 0.4.0 pattern, kept for comparison: build every item into its own message (what `generated` used to copy). */
    @JvmStatic fun generatedItemsOnly(src: Source) = List(src.n) { i ->
        FbItem.fbBuild(id = i.toLong(), name = src.names[i], score = src.scores[i], active = i % 2 == 0)
    }
}

/** Struct-vector payload (numeric items stored inline) vs protobuf's repeated message. */
object NumCodec {
    @JvmStatic
    fun fbHand(src: Source): ByteArray {
        val b = FlatBufferBuilder(src.n * 24 + 64)
        FbNumPayload.startNumericItemsVector(b, src.n)
        for (i in src.n - 1 downTo 0) FbNumItem.createNumItem(b, i.toLong(), src.scores[i], i % 2 == 0)
        val vec = b.endVector()
        b.finish(FbNumPayload.createNumPayload(b, 42L, vec))
        return b.sizedByteArray()
    }

    @JvmStatic
    fun fbGenerated(src: Source): ByteArray = FbNumPayload.fbBuildBytes(
        id = 42L, numericItems = List(src.n) { i -> FbNumItemSpec(i.toLong(), src.scores[i], i % 2 == 0) },
    )

    @JvmStatic
    fun fbVector(src: Source): ByteArray = FbNumPayload.fbBuildBytes(
        id = 42L, numericItems = FbNumItemSpec.Vector(src.n) { i -> put(i.toLong(), src.scores[i], i % 2 == 0) },
    )

    @JvmStatic
    fun pb(src: Source): ByteArray {
        val b = PbNumPayload.newBuilder().setId(42L)
        for (i in 0 until src.n) b.addItems(PbNumItem.newBuilder().setId(i.toLong()).setScore(src.scores[i]).setActive(i % 2 == 0))
        return b.build().toByteArray()
    }

    @JvmStatic
    fun fbReadAll(bytes: ByteArray): Double {
        val p = FbNumPayload.getRootAsNumPayload(java.nio.ByteBuffer.wrap(bytes))
        var acc = p.id.toDouble()
        val item = FbNumItem()
        for (i in 0 until p.numericItemsLength) { p.numericItems(item, i); acc += item.id + item.score + if (item.active) 1 else 0 }
        return acc
    }

    @JvmStatic
    fun pbReadAll(bytes: ByteArray): Double {
        val p = PbNumPayload.parseFrom(bytes)
        var acc = p.id.toDouble()
        for (i in 0 until p.itemsCount) { val it = p.getItems(i); acc += it.id + it.score + if (it.active) 1 else 0 }
        return acc
    }
}

/** Reading the same string field several times: flatbuffers re-decodes UTF-8 each time, protobuf caches. */
object RepeatedReads {
    @JvmStatic
    fun fb(p: FbPayload, times: Int): Long {
        var acc = 0L
        val item = FbItem()
        for (i in 0 until p.itemsLength) {
            p.items(item, i)
            repeat(times) { acc += item.name!!.length }
        }
        return acc
    }

    @JvmStatic
    fun pb(p: PbPayload, times: Int): Long {
        var acc = 0L
        for (i in 0 until p.itemsCount) {
            val item = p.getItems(i)
            repeat(times) { acc += item.name.length }
        }
        return acc
    }

    /** The generated `nameEquals`: compares UTF-8 bytes straight from the buffer, without decoding a String. */
    @JvmStatic
    fun fbEquals(p: FbPayload, times: Int, needle: String): Long {
        var acc = 0L
        val item = FbItem()
        for (i in 0 until p.itemsLength) {
            p.items(item, i)
            repeat(times) { if (item.fbNameEquals(needle)) acc++ }
        }
        return acc
    }

    /** protobuf equivalent of `fbEquals`: `String.equals` on the cached decoded field. */
    @JvmStatic
    fun pbEquals(p: PbPayload, times: Int, needle: String): Long {
        var acc = 0L
        for (i in 0 until p.itemsCount) {
            val item = p.getItems(i)
            repeat(times) { if (item.name == needle) acc++ }
        }
        return acc
    }

    /** What a zero-copy accessor costs: compare bytes without creating a String. */
    @JvmStatic
    fun fbBytesOnly(p: FbPayload, times: Int): Long {
        var acc = 0L
        val item = FbItem()
        for (i in 0 until p.itemsLength) {
            p.items(item, i)
            repeat(times) { acc += item.nameAsByteBuffer.remaining() }
        }
        return acc
    }
}
