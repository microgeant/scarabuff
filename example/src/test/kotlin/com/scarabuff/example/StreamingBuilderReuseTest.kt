package com.scarabuff.example

import com.google.common.truth.Truth.assertThat
import com.google.flatbuffers.FlatBufferBuilder
import com.scarabuff.example.echo.EchoChatServiceGrpcKt.EchoChatServiceCoroutineImplBase
import com.scarabuff.example.echo.EchoChatServiceGrpcKt.EchoChatServiceCoroutineStub
import com.scarabuff.example.echo.EchoRequest
import com.scarabuff.example.echo.EchoResponse
import com.scarabuff.example.echo.EchoStreamServiceGrpcKt.EchoStreamServiceCoroutineImplBase
import com.scarabuff.example.echo.EchoStreamServiceGrpcKt.EchoStreamServiceCoroutineStub
import com.scarabuff.example.echo.build
import io.grpc.BindableService
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.NettyServerBuilder
import io.grpc.okhttp.OkHttpChannelBuilder
import java.net.InetSocketAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Pins down when one FlatBufferBuilder can be reused across streamed messages, as the README
 * describes: a message from `build()` is a view over the builder's buffer, so it's only safe if
 * gRPC has copied it before the next one is built.
 *
 * That holds when each message is built inside the flow gRPC collects (grpc-kotlin sends each
 * element before asking for the next) on transports that copy during `sendMessage`: Netty and
 * OkHttp. It doesn't hold for buffered flows, or for the in-process transport, which hands the
 * bytes over without copying; those corrupt messages, so they're not tested here. This relies on
 * grpc-java's behavior rather than a documented contract, which is why it's checked on every build.
 */
class StreamingBuilderReuseTest {

    private val count = 300
    private lateinit var server: Server
    private lateinit var channel: ManagedChannel

    // Varying lengths, so a message overwritten by a later one can't go unnoticed.
    private fun text(i: Int) = "message-$i-" + "x".repeat(i % 37)

    private fun start(service: BindableService, okHttpClient: Boolean = false) {
        server = NettyServerBuilder.forAddress(InetSocketAddress("127.0.0.1", 0)).addService(service).build().start()
        channel = if (okHttpClient) {
            OkHttpChannelBuilder.forAddress("127.0.0.1", server.port).usePlaintext().build()
        } else {
            NettyChannelBuilder.forAddress("127.0.0.1", server.port).usePlaintext().build()
        }
    }

    @AfterEach
    fun stop() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    /** Built inside the flow, one reused builder for every request. */
    private fun requests(): Flow<EchoRequest> = flow {
        val builder = FlatBufferBuilder(64)
        for (i in 0 until count) emit(EchoRequest.build(message = text(i), builder = builder))
    }

    private val echoService = object : EchoChatServiceCoroutineImplBase() {
        override fun chatEcho(requests: Flow<EchoRequest>): Flow<EchoResponse> =
            requests.map { EchoResponse.build(message = it.message!!) }
    }

    @Test
    fun `a server can reuse one builder for the responses it streams`() = runBlocking {
        start(object : EchoStreamServiceCoroutineImplBase() {
            override fun observeEcho(request: EchoRequest): Flow<EchoResponse> = flow {
                val builder = FlatBufferBuilder(64)
                for (i in 0 until count) emit(EchoResponse.build(message = text(i), builder = builder))
            }
        })

        val received = EchoStreamServiceCoroutineStub(channel).observeEcho(EchoRequest.build(message = "go")).toList()

        assertThat(received.map { it.message }).containsExactlyElementsIn(List(count) { text(it) }).inOrder()
    }

    @Test
    fun `a Netty client can reuse one builder for the requests it streams`() = runBlocking {
        start(echoService)

        val received = EchoChatServiceCoroutineStub(channel).chatEcho(requests()).toList()

        assertThat(received.map { it.message }).containsExactlyElementsIn(List(count) { text(it) }).inOrder()
    }

    @Test
    fun `an OkHttp client, as on Android, can reuse one builder for the requests it streams`() = runBlocking {
        start(echoService, okHttpClient = true)

        val received = EchoChatServiceCoroutineStub(channel).chatEcho(requests()).toList()

        assertThat(received.map { it.message }).containsExactlyElementsIn(List(count) { text(it) }).inOrder()
    }
}
