package com.scarabuff.benchmark;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * FlatBuffers (scarabuff-generated descriptors and marshallers) vs protobuf-java,
 * same data, same transport. Run from benchmark/: ./gradlew jmh
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class WireFormatBenchmark {
    @Param({"1", "50", "1000"})
    public int items;

    Source src;
    byte[] fbBytes, pbBytes;
    com.scarabuff.benchmark.fb.Payload fbMsg;
    com.scarabuff.benchmark.pb.Payload pbMsg;
    Loopback loopback;

    @Setup(Level.Trial)
    public void setup() {
        src = new Source(items);
        fbBytes = FbCodec.encode(src);
        pbBytes = PbCodec.encode(src);
        fbMsg = FbCodec.parse(fbBytes);
        pbMsg = PbCodec.parse(pbBytes);
        if (!java.util.Arrays.equals(FbCodec.encodeGenerated(src), fbBytes)) {
            // Different byte layout is fine (offset order), but it must decode to the same content.
            if (FbCodec.readAll(FbCodec.parse(FbCodec.encodeGenerated(src))) != FbCodec.readAll(fbMsg))
                throw new IllegalStateException("generated builders produce different content");
        }
        System.out.printf("%n[wire size] items=%d  flatbuffers=%d B  protobuf=%d B%n", items, fbBytes.length, pbBytes.length);
        // The names are repetitive (item-N-abcdefghij), so gzip does better here than on most real data.
        System.out.printf("[gzip size] items=%d  flatbuffers=%d B  protobuf=%d B%n",
            items, Gzip.compress(fbBytes).length, Gzip.compress(pbBytes).length);
        loopback = new Loopback();
        // Sanity: both formats must agree on what the server computed, or the comparison is meaningless.
        double expected = FbCodec.readAll(fbMsg) - PbCodec.readAll(pbMsg);
        if (Math.abs(expected) > 1e-6) throw new IllegalStateException("fb/pb decode disagree");
        if (loopback.callFb(fbMsg).getCount() != items || loopback.callPb(pbMsg).getCount() != items)
            throw new IllegalStateException("rpc returned wrong count");
        if (loopback.callFbGzip(fbMsg).getCount() != items || loopback.callPbGzip(pbMsg).getCount() != items)
            throw new IllegalStateException("compressed rpc returned wrong count");
    }

    @TearDown(Level.Trial)
    public void tearDown() { loopback.close(); }

    // 1. Build the message from raw values and serialize to bytes.
    @Benchmark public byte[] encode_flatbuffers() { return FbCodec.encode(src); }
    @Benchmark public byte[] encode_protobuf() { return PbCodec.encode(src); }
    // Same, through scarabuff's generated Payload.buildBytes(...) instead of hand-written builder calls.
    @Benchmark public byte[] encodeGenerated_flatbuffers() { return FbCodec.encodeGenerated(src); }

    // 2. From wire bytes, read just two fields (id + item count).
    @Benchmark public long decodePartial_flatbuffers() { return FbCodec.readPartial(FbCodec.parse(fbBytes)); }
    @Benchmark public long decodePartial_protobuf() { return PbCodec.readPartial(PbCodec.parse(pbBytes)); }

    // 3. From wire bytes, read every field of every item.
    @Benchmark public double decodeAll_flatbuffers() { return FbCodec.readAll(FbCodec.parse(fbBytes)); }
    @Benchmark public double decodeAll_protobuf() { return PbCodec.readAll(PbCodec.parse(pbBytes)); }

    // 4. gRPC marshaller round trip (message -> bytes -> message), then read everything.
    @Benchmark public double marshal_flatbuffers() { return FbCodec.readAll(FbCodec.marshalRoundTrip(fbMsg)); }
    @Benchmark public double marshal_protobuf() { return PbCodec.readAll(PbCodec.marshalRoundTrip(pbMsg)); }

    // 5. Full unary RPC over loopback Netty; server reads every item; request is pre-built.
    @Benchmark public Object rpc_flatbuffers() { return loopback.callFb(fbMsg); }
    @Benchmark public Object rpc_protobuf() { return loopback.callPb(pbMsg); }

    // 6. The same RPC with the request gzip-compressed (CallOptions.withCompression("gzip")).
    @Benchmark public Object rpcGzip_flatbuffers() { return loopback.callFbGzip(fbMsg); }
    @Benchmark public Object rpcGzip_protobuf() { return loopback.callPbGzip(pbMsg); }

    // 7. Compressing one message's bytes, the CPU cost compression adds per message.
    @Benchmark public byte[] gzip_flatbuffers() { return Gzip.compress(fbBytes); }
    @Benchmark public byte[] gzip_protobuf() { return Gzip.compress(pbBytes); }
}
