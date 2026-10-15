package com.scarabuff.example

import com.google.common.truth.Truth.assertThat
import com.scarabuff.example.echo.EchoChatServiceGrpcKt.EchoChatServiceCoroutineImplBase
import com.scarabuff.example.echo.EchoChatServiceGrpcKt.EchoChatServiceCoroutineStub
import com.scarabuff.example.echo.EchoCollectServiceGrpcKt.EchoCollectServiceCoroutineImplBase
import com.scarabuff.example.echo.EchoCollectServiceGrpcKt.EchoCollectServiceCoroutineStub
import com.scarabuff.example.echo.EchoRequest
import com.scarabuff.example.echo.EchoResponse
import com.scarabuff.example.echo.EchoServiceGrpcKt.EchoServiceCoroutineImplBase
import com.scarabuff.example.echo.EchoServiceGrpcKt.EchoServiceCoroutineStub
import com.scarabuff.example.echo.EchoStreamServiceGrpcKt.EchoStreamServiceCoroutineImplBase
import com.scarabuff.example.echo.EchoStreamServiceGrpcKt.EchoStreamServiceCoroutineStub
import com.scarabuff.example.echo.build
import io.grpc.BindableService
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Proves scarabuff's generated coroutine stubs actually perform real gRPC calls end-to-end, for
 * all four streaming kinds — not just that the generated Kotlin compiles.
 */
class EchoRoundTripTest {

    private lateinit var server: Server
    private lateinit var channel: ManagedChannel

    private fun startServer(service: BindableService) {
        val name = InProcessServerBuilder.generateName()
        server = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start()
        channel = InProcessChannelBuilder.forName(name).directExecutor().build()
    }

    @AfterEach
    fun tearDown() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    @Test
    fun `unary echo`() = runBlocking {
        startServer(object : EchoServiceCoroutineImplBase() {
            override suspend fun echo(request: EchoRequest): EchoResponse =
                echoResponse("echo: ${request.message}")
        })

        val response = EchoServiceCoroutineStub(channel).echo(echoRequest("hello"))

        assertThat(response.message).isEqualTo("echo: hello")
    }

    @Test
    fun `per-call headers reach the server`() = runBlocking {
        val traceKey = Metadata.Key.of("x-trace-id", Metadata.ASCII_STRING_MARSHALLER)
        var receivedTraceId: String? = null
        val captureHeaders = object : ServerInterceptor {
            override fun <ReqT, RespT> interceptCall(
                call: ServerCall<ReqT, RespT>,
                headers: Metadata,
                next: ServerCallHandler<ReqT, RespT>,
            ): ServerCall.Listener<ReqT> {
                receivedTraceId = headers.get(traceKey)
                return next.startCall(call, headers)
            }
        }
        val service = object : EchoServiceCoroutineImplBase() {
            override suspend fun echo(request: EchoRequest): EchoResponse = echoResponse(request.message!!)
        }
        val name = InProcessServerBuilder.generateName()
        server = InProcessServerBuilder.forName(name).directExecutor()
            .addService(ServerInterceptors.intercept(service, captureHeaders)).build().start()
        channel = InProcessChannelBuilder.forName(name).directExecutor().build()

        val headers = Metadata().apply { put(traceKey, "abc123") }
        EchoServiceCoroutineStub(channel).echo(echoRequest("hi"), headers)

        assertThat(receivedTraceId).isEqualTo("abc123")
    }

    @Test
    fun `server streaming echo`() = runBlocking {
        startServer(object : EchoStreamServiceCoroutineImplBase() {
            override fun observeEcho(request: EchoRequest): Flow<EchoResponse> =
                flowOf(1, 2, 3).map { echoResponse("${request.message}-$it") }
        })

        val responses = EchoStreamServiceCoroutineStub(channel).observeEcho(echoRequest("tick")).toList()

        assertThat(responses.map { it.message }).containsExactly("tick-1", "tick-2", "tick-3").inOrder()
    }

    @Test
    fun `client streaming echo`() = runBlocking {
        startServer(object : EchoCollectServiceCoroutineImplBase() {
            override suspend fun collectEcho(requests: Flow<EchoRequest>): EchoResponse {
                val messages = requests.toList().map { it.message }
                return echoResponse(messages.joinToString(","))
            }
        })

        val requests = flowOf("a", "b", "c").map { echoRequest(it) }
        val response = EchoCollectServiceCoroutineStub(channel).collectEcho(requests)

        assertThat(response.message).isEqualTo("a,b,c")
    }

    @Test
    fun `bidi streaming echo`() = runBlocking {
        startServer(object : EchoChatServiceCoroutineImplBase() {
            override fun chatEcho(requests: Flow<EchoRequest>): Flow<EchoResponse> =
                requests.map { echoResponse("chat:${it.message}") }
        })

        val requests = flowOf("x", "y").map { echoRequest(it) }
        val responses = EchoChatServiceCoroutineStub(channel).chatEcho(requests).toList()

        assertThat(responses.map { it.message }).containsExactly("chat:x", "chat:y").inOrder()
    }

    // build() is generated by scarabuff for every table (see EchoRequestBuilders.kt in the build dir)
    private fun echoRequest(message: String) = EchoRequest.build(message = message)

    private fun echoResponse(message: String) = EchoResponse.build(message = message)
}
