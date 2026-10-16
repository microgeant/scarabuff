package com.scarabuff.benchmark

import com.google.flatbuffers.FlatBufferBuilder
import com.scarabuff.benchmark.fb.BenchServiceGrpcKt
import com.scarabuff.benchmark.fb.Item as FbItem
import com.scarabuff.benchmark.fb.ItemSpec as FbItemSpec
import com.scarabuff.benchmark.fb.Payload as FbPayload
import com.scarabuff.benchmark.fb.Summary as FbSummary
import com.scarabuff.benchmark.fb.build as fbBuild
import com.scarabuff.benchmark.fb.buildBytes as fbBuildBytes
import com.scarabuff.benchmark.pb.Item as PbItem
import com.scarabuff.benchmark.pb.Payload as PbPayload
import io.grpc.MethodDescriptor
import io.grpc.protobuf.ProtoUtils
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

/** What gRPC transports hand marshallers: a stream that knows its length. */
class WireStream(bytes: ByteArray) : ByteArrayInputStream(bytes), io.grpc.KnownLength

/** Deterministic input shared by both encoders, so both serialize exactly the same data. */
class Source(val n: Int) {
    val names = Array(n) { "item-$it-abcdefghij" }
    val scores = DoubleArray(n) { it * 1.5 }
}

/** gzip, as gRPC's `gzip` compressor applies it to each message. */
object Gzip {
    @JvmStatic
    fun compress(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(bytes.size / 2 + 64)
        java.util.zip.GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }
}

/** What the "server" computes from a received payload; identical for both formats. */
class Sum(var count: Int = 0, var sum: Double = 0.0)

object FbCodec {
    @JvmStatic
    fun encode(src: Source): ByteArray {
        val b = FlatBufferBuilder(1024)
        val items = IntArray(src.n) { i ->
            FbItem.createItem(b, i.toLong(), b.createString(src.names[i]), src.scores[i], i % 2 == 0)
        }
        val vec = FbPayload.createItemsVector(b, items)
        b.finish(FbPayload.createPayload(b, 42L, b.createString("benchmark payload"), 1_700_000_000_000L, vec))
        return b.sizedByteArray()
    }

    /** Same data through scarabuff's generated `Payload.buildBytes(...)` (what app code would write). */
    @JvmStatic
    fun encodeGenerated(src: Source): ByteArray = FbPayload.fbBuildBytes(
        id = 42L,
        title = "benchmark payload",
        timestampMs = 1_700_000_000_000L,
        items = List(src.n) { i -> FbItemSpec(id = i.toLong(), name = src.names[i], score = src.scores[i], active = i % 2 == 0) },
    )

    @JvmStatic fun parse(bytes: ByteArray): FbPayload = FbPayload.getRootAsPayload(ByteBuffer.wrap(bytes))

    @JvmStatic fun readPartial(p: FbPayload): Long = p.id + p.itemsLength

    @JvmStatic
    fun readAll(p: FbPayload): Double {
        var acc = p.id.toDouble() + (p.title?.length ?: 0) + p.timestampMs
        val item = FbItem()
        for (i in 0 until p.itemsLength) {
            p.items(item, i)
            acc += item.id + (item.name?.length ?: 0) + item.score + if (item.active) 1 else 0
        }
        return acc
    }

    private val marshaller = BenchServiceGrpcKt.getProcessMethod.requestMarshaller

    /** Marshaller path scarabuff's generated descriptor uses: message -> wire bytes -> message. */
    @JvmStatic
    fun marshalRoundTrip(p: FbPayload): FbPayload {
        val wire = marshaller.stream(p).readAllBytes()
        return marshaller.parse(WireStream(wire))
    }

    @JvmStatic val method: MethodDescriptor<FbPayload, FbSummary> get() = BenchServiceGrpcKt.getProcessMethod
}

object PbCodec {
    @JvmStatic
    fun encode(src: Source): ByteArray {
        val b = PbPayload.newBuilder().setId(42L).setTitle("benchmark payload").setTimestampMs(1_700_000_000_000L)
        for (i in 0 until src.n) {
            b.addItems(PbItem.newBuilder().setId(i.toLong()).setName(src.names[i]).setScore(src.scores[i]).setActive(i % 2 == 0))
        }
        return b.build().toByteArray()
    }

    @JvmStatic fun parse(bytes: ByteArray): PbPayload = PbPayload.parseFrom(bytes)

    @JvmStatic fun readPartial(p: PbPayload): Long = p.id + p.itemsCount

    @JvmStatic
    fun readAll(p: PbPayload): Double {
        var acc = p.id.toDouble() + p.title.length + p.timestampMs
        for (i in 0 until p.itemsCount) {
            val item = p.getItems(i)
            acc += item.id + item.name.length + item.score + if (item.active) 1 else 0
        }
        return acc
    }

    private val marshaller = ProtoUtils.marshaller(PbPayload.getDefaultInstance())

    @JvmStatic
    fun marshalRoundTrip(p: PbPayload): PbPayload {
        val wire = marshaller.stream(p).readAllBytes()
        return marshaller.parse(WireStream(wire))
    }

    @JvmStatic
    fun method(): MethodDescriptor<PbPayload, com.scarabuff.benchmark.pb.Summary> =
        MethodDescriptor.newBuilder<PbPayload, com.scarabuff.benchmark.pb.Summary>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName("bench.PbBenchService", "Process"))
            .setRequestMarshaller(marshaller)
            .setResponseMarshaller(ProtoUtils.marshaller(com.scarabuff.benchmark.pb.Summary.getDefaultInstance()))
            .build()
}
