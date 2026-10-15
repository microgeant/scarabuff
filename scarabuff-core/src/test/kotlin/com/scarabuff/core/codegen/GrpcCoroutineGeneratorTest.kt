package com.scarabuff.core.codegen

import com.google.common.truth.Truth.assertThat
import com.scarabuff.core.fbs.FbsTypeName
import com.scarabuff.core.fbs.RpcMethod
import com.scarabuff.core.fbs.RpcServiceDef
import com.scarabuff.core.fbs.StreamingKind
import org.junit.jupiter.api.Test

class GrpcCoroutineGeneratorTest {

    private val namespace = "com.example.echo"

    private fun type(simpleName: String) = FbsTypeName(namespace, simpleName)

    private fun service(name: String, methodName: String, streaming: StreamingKind) = RpcServiceDef(
        namespace = namespace,
        name = name,
        methods = listOf(
            RpcMethod(
                name = methodName,
                requestType = type("EchoRequest"),
                responseType = type("EchoResponse"),
                streaming = streaming,
            )
        ),
    )

    @Test
    fun `unary generates suspend stub method and unaryRpc call`() {
        val spec = GrpcCoroutineGenerator.generate(
            service("EchoService", "Echo", StreamingKind.UNARY),
        ).toString()

        assertThat(spec).contains("object EchoServiceGrpcKt")
        assertThat(spec).contains("class EchoServiceCoroutineStub")
        assertThat(spec).contains("abstract class EchoServiceCoroutineImplBase")
        assertThat(spec).contains("suspend fun echo(request: EchoRequest, headers: Metadata = Metadata()): EchoResponse")
        assertThat(spec).contains("ClientCalls.unaryRpc(")
        assertThat(spec).contains("ServerCalls.unaryServerMethodDefinition(")
    }

    @Test
    fun `method descriptor is built directly, not delegated to a flatc-generated Java class`() {
        val spec = GrpcCoroutineGenerator.generate(
            service("EchoService", "Echo", StreamingKind.UNARY),
        ).toString()

        assertThat(spec).contains("val getEchoMethod")
        assertThat(spec).contains("MethodDescriptor.newBuilder<EchoRequest, EchoResponse>()")
        assertThat(spec).contains("MethodDescriptor.MethodType.UNARY")
        assertThat(spec).contains("MethodDescriptor.generateFullMethodName(SERVICE_NAME, \"Echo\")")
        assertThat(spec).contains("FlatbuffersUtils.marshaller(EchoRequest::class.java")
        assertThat(spec).contains("EchoRequest.getRootAsEchoRequest(buffer)")
        assertThat(spec).doesNotContain("Grpc.get")
    }

    @Test
    fun `service descriptor is built from the generated method descriptors`() {
        val spec = GrpcCoroutineGenerator.generate(
            service("EchoService", "Echo", StreamingKind.UNARY),
        ).toString()

        assertThat(spec).contains("val serviceDescriptor")
        assertThat(spec).contains("ServiceDescriptor.newBuilder(SERVICE_NAME)")
        assertThat(spec).contains(".addMethod(getEchoMethod)")
        assertThat(spec).contains("private const val SERVICE_NAME: String = \"com.example.echo.EchoService\"")
    }

    @Test
    fun `server streaming returns Flow from a non-suspend stub method`() {
        val spec = GrpcCoroutineGenerator.generate(
            service("EchoStreamService", "ObserveEcho", StreamingKind.SERVER),
        ).toString()

        assertThat(spec).contains("fun observeEcho(request: EchoRequest, headers: Metadata = Metadata()): Flow<EchoResponse>")
        assertThat(spec).doesNotContain("suspend fun observeEcho")
        assertThat(spec).contains("ClientCalls.serverStreamingRpc(")
        assertThat(spec).contains("ServerCalls.serverStreamingServerMethodDefinition(")
        assertThat(spec).contains("MethodDescriptor.MethodType.SERVER_STREAMING")
    }

    @Test
    fun `client streaming takes a Flow request and is suspend`() {
        val spec = GrpcCoroutineGenerator.generate(
            service("EchoClientService", "CollectEcho", StreamingKind.CLIENT),
        ).toString()

        // KotlinPoet may wrap the return type onto the next line, so check the pieces separately
        assertThat(spec).contains("suspend fun collectEcho(requests: Flow<EchoRequest>, headers: Metadata = Metadata()):")
        assertThat(spec).contains("EchoResponse = ClientCalls.clientStreamingRpc(")
        assertThat(spec).contains("ClientCalls.clientStreamingRpc(")
        assertThat(spec).contains("ServerCalls.clientStreamingServerMethodDefinition(")
        assertThat(spec).contains("MethodDescriptor.MethodType.CLIENT_STREAMING")
    }

    @Test
    fun `bidi streaming takes and returns Flow, non-suspend`() {
        val spec = GrpcCoroutineGenerator.generate(
            service("EchoBidiService", "ChatEcho", StreamingKind.BIDI),
        ).toString()

        assertThat(spec).contains("fun chatEcho(requests: Flow<EchoRequest>, headers: Metadata = Metadata()):")
        assertThat(spec).contains("Flow<EchoResponse> = ClientCalls.bidiStreamingRpc(")
        assertThat(spec).doesNotContain("suspend fun chatEcho")
        assertThat(spec).contains("ClientCalls.bidiStreamingRpc(")
        assertThat(spec).contains("ServerCalls.bidiStreamingServerMethodDefinition(")
        assertThat(spec).contains("MethodDescriptor.MethodType.BIDI_STREAMING")
    }

    @Test
    fun `kotlinPackage defaults to the service namespace`() {
        val spec = GrpcCoroutineGenerator.generate(
            service("EchoService", "Echo", StreamingKind.UNARY),
        )
        assertThat(spec.packageName).isEqualTo(namespace)
    }

    @Test
    fun `kotlinPackage can be overridden independently of the namespace`() {
        val spec = GrpcCoroutineGenerator.generate(
            service("EchoService", "Echo", StreamingKind.UNARY),
            kotlinPackage = "com.example.echo.coroutines",
        )
        assertThat(spec.packageName).isEqualTo("com.example.echo.coroutines")
        assertThat(spec.toString()).contains("import com.example.echo.EchoRequest")
    }

    @Test
    fun `stub passes per-call headers through to the call`() {
        val spec = GrpcCoroutineGenerator.generate(
            service("EchoService", "Echo", StreamingKind.UNARY),
        ).toString()

        assertThat(spec).contains("callOptions,\n      headers\n")
        assertThat(spec).doesNotContain("callOptions,\n      Metadata()")
    }

    @Test
    fun `server methods take request or requests without a headers parameter`() {
        val unary = GrpcCoroutineGenerator.generate(service("EchoService", "Echo", StreamingKind.UNARY))
            .toString()
        val bidi = GrpcCoroutineGenerator.generate(service("EchoBidiService", "ChatEcho", StreamingKind.BIDI))
            .toString()

        assertThat(unary).contains("open suspend fun echo(request: EchoRequest): EchoResponse")
        assertThat(bidi).contains("open fun chatEcho(requests: Flow<EchoRequest>): Flow<EchoResponse>")
    }

    @Test
    fun `request type named Channel does not shadow the stub's channel`() {
        val spec = GrpcCoroutineGenerator.generate(
            RpcServiceDef(
                namespace = namespace,
                name = "ChannelService",
                methods = listOf(RpcMethod("Join", type("Channel"), type("CallOptions"), StreamingKind.BIDI)),
            ),
        ).toString()

        assertThat(spec).contains("fun join(requests: Flow<Channel>, headers: Metadata = Metadata())")
        assertThat(spec).contains("channel,\n      getJoinMethod,\n      requests,\n")
    }

    @Test
    fun `types from another namespace are referenced from their own package`() {
        val spec = GrpcCoroutineGenerator.generate(
            RpcServiceDef(
                namespace = "com.example.svc",
                name = "PingService",
                methods = listOf(
                    RpcMethod("Ping", FbsTypeName("com.example.common", "Ping"), FbsTypeName("com.example.svc", "Pong"), StreamingKind.UNARY),
                ),
            ),
        )
        val code = spec.toString()

        assertThat(spec.packageName).isEqualTo("com.example.svc")
        assertThat(code).contains("import com.example.common.Ping")
        assertThat(code).contains("MethodDescriptor.newBuilder<Ping, Pong>()")
        assertThat(code).contains("\"com.example.svc.PingService\"")
    }

    @Test
    fun `schema without a namespace generates into the root package`() {
        val spec = GrpcCoroutineGenerator.generate(
            RpcServiceDef(
                namespace = "",
                name = "RootService",
                methods = listOf(RpcMethod("Ping", FbsTypeName("", "Ping"), FbsTypeName("", "Pong"), StreamingKind.UNARY)),
            ),
        )

        assertThat(spec.packageName).isEmpty()
        assertThat(spec.toString()).contains("SERVICE_NAME: String = \"RootService\"")
    }
}
