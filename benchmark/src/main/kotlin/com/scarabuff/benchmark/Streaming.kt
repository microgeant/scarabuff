package com.scarabuff.benchmark

import com.scarabuff.benchmark.fb.Item as FbItem
import com.scarabuff.benchmark.fb.StreamServiceGrpcKt
import com.scarabuff.benchmark.fb.StreamServiceGrpcKt.StreamServiceCoroutineStub
import com.scarabuff.benchmark.fb.Summary as FbSummary
import com.scarabuff.benchmark.fb.build as fbBuild
import com.scarabuff.benchmark.pb.Item as PbItem
import com.scarabuff.benchmark.pb.Summary as PbSummary
import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor
import io.grpc.Server
import io.grpc.ServerServiceDefinition
import io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.ProtoUtils
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * Coroutine streaming over loopback Netty with identical server logic in both formats: scarabuff's
 * generated stub/ImplBase vs grpc-kotlin's ClientCalls/ServerCalls with protobuf marshallers.
 */
class Streaming : AutoCloseable {
    private fun <Q, R> pbMethod(name: String, type: MethodDescriptor.MethodType, q: Q, r: R): MethodDescriptor<Q, R>
        where Q : com.google.protobuf.Message, R : com.google.protobuf.Message =
        MethodDescriptor.newBuilder<Q, R>().setType(type)
            .setFullMethodName(MethodDescriptor.generateFullMethodName("bench.PbStream", name))
            .setRequestMarshaller(ProtoUtils.marshaller(q)).setResponseMarshaller(ProtoUtils.marshaller(r)).build()

    private val pbProduce = pbMethod("Produce", MethodDescriptor.MethodType.SERVER_STREAMING, PbSummary.getDefaultInstance(), PbItem.getDefaultInstance())
    private val pbEcho = pbMethod("Echo", MethodDescriptor.MethodType.BIDI_STREAMING, PbItem.getDefaultInstance(), PbItem.getDefaultInstance())

    private val fbService = object : StreamServiceGrpcKt.StreamServiceCoroutineImplBase() {
        override fun produce(request: FbSummary): Flow<FbItem> = flow {
            for (i in 0 until request.count) emit(FbItem.fbBuild(id = i.toLong(), name = "item-$i", score = 1.5, active = true))
        }
        override fun echo(requests: Flow<FbItem>): Flow<FbItem> =
            requests.map { FbItem.fbBuild(id = it.id, name = it.name ?: "", score = it.score, active = it.active) }
    }

    private val pbService: ServerServiceDefinition = ServerServiceDefinition.builder("bench.PbStream")
        .addMethod(io.grpc.kotlin.ServerCalls.serverStreamingServerMethodDefinition(EmptyCoroutineContext, pbProduce) { req: PbSummary ->
            flow { for (i in 0 until req.count) emit(PbItem.newBuilder().setId(i.toLong()).setName("item-$i").setScore(1.5).setActive(true).build()) }
        })
        .addMethod(io.grpc.kotlin.ServerCalls.bidiStreamingServerMethodDefinition(EmptyCoroutineContext, pbEcho) { reqs: Flow<PbItem> ->
            reqs.map { PbItem.newBuilder().setId(it.id).setName(it.name).setScore(it.score).setActive(it.active).build() }
        })
        .build()

    private val server: Server = NettyServerBuilder.forAddress(InetSocketAddress("127.0.0.1", 0))
        .addService(fbService).addService(pbService).build().start()
    private val channel: ManagedChannel = NettyChannelBuilder.forAddress("127.0.0.1", server.port).usePlaintext().build()
    private val fbStub = StreamServiceCoroutineStub(channel)

    fun serverStreamFb(n: Int): Long = runBlocking {
        var acc = 0L
        fbStub.produce(FbSummary.fbBuild(count = n, sum = 0.0)).collect { acc += it.id }
        acc
    }

    fun serverStreamPb(n: Int): Long = runBlocking {
        var acc = 0L
        io.grpc.kotlin.ClientCalls.serverStreamingRpc(channel, pbProduce, PbSummary.newBuilder().setCount(n).build())
            .collect { acc += it.id }
        acc
    }

    fun bidiFb(n: Int): Long = runBlocking {
        var acc = 0L
        fbStub.echo((0 until n).asFlow().map { FbItem.fbBuild(id = it.toLong(), name = "item-$it", score = 1.5, active = true) })
            .collect { acc += it.id }
        acc
    }

    fun bidiPb(n: Int): Long = runBlocking {
        var acc = 0L
        io.grpc.kotlin.ClientCalls.bidiStreamingRpc(channel, pbEcho,
            (0 until n).asFlow().map { PbItem.newBuilder().setId(it.toLong()).setName("item-$it").setScore(1.5).setActive(true).build() })
            .collect { acc += it.id }
        acc
    }

    override fun close() {
        channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)
        server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)
    }
}
