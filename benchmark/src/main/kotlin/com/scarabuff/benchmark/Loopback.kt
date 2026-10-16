package com.scarabuff.benchmark

import com.scarabuff.benchmark.fb.Summary as FbSummary
import com.scarabuff.benchmark.fb.build as fbBuild
import com.scarabuff.benchmark.pb.Payload as PbPayload
import com.scarabuff.benchmark.pb.Summary as PbSummary
import io.grpc.CallOptions
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.ServerServiceDefinition
import io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.NettyServerBuilder
import io.grpc.stub.ClientCalls
import io.grpc.stub.ServerCalls
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import com.scarabuff.benchmark.fb.Payload as FbPayload

/**
 * Real Netty gRPC server + channel on loopback, one unary RPC per format. Both servers read every
 * field of the request and return the same small summary. Plain gRPC handlers (no coroutines) are
 * used on both sides so only the wire format differs.
 */
class Loopback : AutoCloseable {
    private val fbMethod = FbCodec.method
    private val pbMethod = PbCodec.method()

    private val server: Server = NettyServerBuilder.forAddress(InetSocketAddress("127.0.0.1", 0))
        .addService(
            ServerServiceDefinition.builder("com.scarabuff.benchmark.fb.BenchService")
                .addMethod(fbMethod, ServerCalls.asyncUnaryCall<FbPayload, FbSummary> { req, obs ->
                    val sum = Sum(req.itemsLength)
                    val item = com.scarabuff.benchmark.fb.Item()
                    for (i in 0 until req.itemsLength) { req.items(item, i); sum.sum += item.score }
                    obs.onNext(FbSummary.fbBuild(count = sum.count, sum = sum.sum))
                    obs.onCompleted()
                }).build(),
        )
        .addService(
            ServerServiceDefinition.builder("bench.PbBenchService")
                .addMethod(pbMethod, ServerCalls.asyncUnaryCall<PbPayload, PbSummary> { req, obs ->
                    var s = 0.0
                    for (i in 0 until req.itemsCount) s += req.getItems(i).score
                    obs.onNext(PbSummary.newBuilder().setCount(req.itemsCount).setSum(s).build())
                    obs.onCompleted()
                }).build(),
        )
        .build().start()

    private val channel: ManagedChannel =
        NettyChannelBuilder.forAddress("127.0.0.1", server.port).usePlaintext().build()

    fun callFb(req: FbPayload): FbSummary = ClientCalls.blockingUnaryCall(channel, fbMethod, CallOptions.DEFAULT, req)
    fun callPb(req: PbPayload): PbSummary = ClientCalls.blockingUnaryCall(channel, pbMethod, CallOptions.DEFAULT, req)

    /** The request sent gzip-compressed, as `stub.withCompression("gzip")` does; grpc-java decompresses it. */
    private val gzip = CallOptions.DEFAULT.withCompression("gzip")
    fun callFbGzip(req: FbPayload): FbSummary = ClientCalls.blockingUnaryCall(channel, fbMethod, gzip, req)
    fun callPbGzip(req: PbPayload): PbSummary = ClientCalls.blockingUnaryCall(channel, pbMethod, gzip, req)

    override fun close() {
        channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)
        server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)
    }
}
