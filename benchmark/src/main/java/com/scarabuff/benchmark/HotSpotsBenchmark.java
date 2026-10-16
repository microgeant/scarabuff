package com.scarabuff.benchmark;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Targeted experiments to find where scarabuff/FlatBuffers can still beat protobuf. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class HotSpotsBenchmark {
    @Param({"1", "50", "1000"})
    public int items;

    Source src;
    byte[] numFb, numPb;
    com.scarabuff.benchmark.fb.Payload fbMsg;
    com.scarabuff.benchmark.pb.Payload pbMsg;
    byte[] fbBytes, pbBytes;
    Loopback loopback;
    Streaming streaming;

    @Setup(Level.Trial)
    public void setup() {
        src = new Source(items);
        fbBytes = FbCodec.encode(src);
        pbBytes = PbCodec.encode(src);
        fbMsg = FbCodec.parse(fbBytes);
        pbMsg = PbCodec.parse(pbBytes);
        numFb = NumCodec.fbHand(src);
        numPb = NumCodec.pb(src);
        System.out.printf("%n[wire size] items=%d  table: fb=%d pb=%d | struct-vs-numeric: fb=%d pb=%d%n",
            items, fbBytes.length, pbBytes.length, numFb.length, numPb.length);
        if (NumCodec.fbReadAll(numFb) != NumCodec.pbReadAll(numPb)) throw new IllegalStateException("num mismatch");
        if (NumCodec.fbReadAll(NumCodec.fbGenerated(src)) != NumCodec.fbReadAll(numFb)) throw new IllegalStateException("num gen mismatch");
        if (!java.util.Arrays.equals(NumCodec.fbVector(src), NumCodec.fbGenerated(src))) throw new IllegalStateException("num vector mismatch");
        if (!java.util.Arrays.equals(EncodeVariants.generatedVector(src), EncodeVariants.generated(src))) throw new IllegalStateException("table vector mismatch");
        loopback = new Loopback();
        streaming = new Streaming();
        if (streaming.serverStreamFb(10) != streaming.serverStreamPb(10)) throw new IllegalStateException("stream mismatch");
        if (streaming.bidiFb(10) != streaming.bidiPb(10)) throw new IllegalStateException("bidi mismatch");
    }

    @TearDown(Level.Trial)
    public void tearDown() { loopback.close(); streaming.close(); }

    // --- E1: where does the generated builder lose time? -------------------------------------
    @Benchmark public byte[] enc_1_handDefault() { return EncodeVariants.handDefault(src); }
    @Benchmark public byte[] enc_2_handPresized() { return EncodeVariants.handPresized(src); }
    @Benchmark public byte[] enc_3_handReusedBuilder() { return EncodeVariants.handReused(src); }
    @Benchmark public byte[] enc_4_packItemsShared() { return EncodeVariants.packItemsShared(src); }
    @Benchmark public byte[] enc_5_generated() { return EncodeVariants.generated(src); }
    @Benchmark public byte[] enc_5c_generatedVector() { return EncodeVariants.generatedVector(src); }
    @Benchmark public byte[] enc_5b_generatedReusedBuilder() { return EncodeVariants.generatedReused(src); }
    @Benchmark public Object enc_6_generatedItemsOnly() { return EncodeVariants.generatedItemsOnly(src); }
    @Benchmark public byte[] enc_7_protobuf() { return PbCodec.encode(src); }

    // --- E2: numeric items as a FlatBuffers struct vector vs protobuf repeated message -------
    @Benchmark public byte[] num_encode_fbHand() { return NumCodec.fbHand(src); }
    @Benchmark public byte[] num_encode_fbGenerated() { return NumCodec.fbGenerated(src); }
    @Benchmark public byte[] num_encode_fbVector() { return NumCodec.fbVector(src); }
    @Benchmark public byte[] num_encode_pb() { return NumCodec.pb(src); }
    @Benchmark public double num_decodeAll_fb() { return NumCodec.fbReadAll(numFb); }
    @Benchmark public double num_decodeAll_pb() { return NumCodec.pbReadAll(numPb); }

    // --- E3: reading a string field 1x and 5x per item --------------------------------------
    @Benchmark public long str_read1_fb() { return RepeatedReads.fb(fbMsg, 1); }
    @Benchmark public long str_read1_pb() { return RepeatedReads.pb(pbMsg, 1); }
    @Benchmark public long str_read5_fb() { return RepeatedReads.fb(fbMsg, 5); }
    @Benchmark public long str_read5_pb() { return RepeatedReads.pb(pbMsg, 5); }
    @Benchmark public long str_read5_fbBytesOnly() { return RepeatedReads.fbBytesOnly(fbMsg, 5); }
    @Benchmark public long str_equals5_fb() { return RepeatedReads.fbEquals(fbMsg, 5, "item-7-abcdefghij"); }
    @Benchmark public long str_equals5_pb() { return RepeatedReads.pbEquals(pbMsg, 5, "item-7-abcdefghij"); }

    // --- E4: marshaller cost alone (message -> bytes -> message, no field reads) --------------
    @Benchmark public Object marshalOnly_fb() { return FbCodec.marshalRoundTrip(fbMsg); }
    @Benchmark public Object marshalOnly_pb() { return PbCodec.marshalRoundTrip(pbMsg); }

    // --- E5: concurrent unary throughput (8 threads), per call -------------------------------
    @Benchmark @Threads(8) public Object rpc8_fb() { return loopback.callFb(fbMsg); }
    @Benchmark @Threads(8) public Object rpc8_pb() { return loopback.callPb(pbMsg); }

    // --- E6: coroutine streaming through generated stubs vs grpc-kotlin+protobuf, per message --
    @Benchmark @OperationsPerInvocation(1000) public long stream_server_fb() { return streaming.serverStreamFb(1000); }
    @Benchmark @OperationsPerInvocation(1000) public long stream_server_pb() { return streaming.serverStreamPb(1000); }
    @Benchmark @OperationsPerInvocation(1000) public long stream_bidi_fb() { return streaming.bidiFb(1000); }
    @Benchmark @OperationsPerInvocation(1000) public long stream_bidi_pb() { return streaming.bidiPb(1000); }
}
