package com.scarabuff.core.codegen

import com.google.common.truth.Truth.assertThat
import com.scarabuff.core.fbs.RpcMethod
import com.scarabuff.core.fbs.RpcServiceDef
import com.scarabuff.core.fbs.StreamingKind
import org.junit.jupiter.api.Test

class GrpcCoroutineGeneratorTest {

    private val dataPackage = "com.example.echo"

    private fun service(name: String, methodName: String, streaming: StreamingKind) = RpcServiceDef(
        name = name,
        methods = listOf(
            RpcMethod(
                name = methodName,
                requestType = "EchoRequest",
                responseType = "EchoResponse",
                streaming = streaming,
            )
        ),
    )

    @Test
    fun `unary generates suspend stub method and unaryRpc call`() {
        val spec = GrpcCoroutineGenerator.generate(
            service("EchoService", "Echo", StreamingKind.UNARY),
            dataPackage,
        ).toString()

        assertThat(spec).contains("object EchoServiceGrpcKt")
        assertThat(spec).contains("class EchoServiceCoroutineStub")
        assertThat(spec).contains("abstract class EchoServiceCoroutineImplBase")
        assertThat(spec).contains("suspend fun echo(echoRequest: EchoRequest): EchoResponse")
        assertThat(spec).contains("ClientCalls.unaryRpc(")
        assertThat(spec).contains("ServerCalls.unaryServerMethodDefinition(")
    }

    @Test
    fun `method descriptor is built directly, not delegated to a flatc-generated Java class`() {
        val spec = GrpcCoroutineGenerator.generate(
            service("EchoService", "Echo", StreamingKind.UNARY),
            dataPackage,
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
            dataPackage,
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
            dataPackage,
        ).toString()

        assertThat(spec).contains("fun observeEcho(echoRequest: EchoRequest): Flow<EchoResponse>")
        assertThat(spec).doesNotContain("suspend fun observeEcho")
        assertThat(spec).contains("ClientCalls.serverStreamingRpc(")
        assertThat(spec).contains("ServerCalls.serverStreamingServerMethodDefinition(")
        assertThat(spec).contains("MethodDescriptor.MethodType.SERVER_STREAMING")
    }

    @Test
    fun `client streaming takes a Flow request and is suspend`() {
        val spec = GrpcCoroutineGenerator.generate(
            service("EchoClientService", "CollectEcho", StreamingKind.CLIENT),
            dataPackage,
        ).toString()

        assertThat(spec).contains("suspend fun collectEcho(echoRequest: Flow<EchoRequest>): EchoResponse")
        assertThat(spec).contains("ClientCalls.clientStreamingRpc(")
        assertThat(spec).contains("ServerCalls.clientStreamingServerMethodDefinition(")
        assertThat(spec).contains("MethodDescriptor.MethodType.CLIENT_STREAMING")
    }

    @Test
    fun `bidi streaming takes and returns Flow, non-suspend`() {
        val spec = GrpcCoroutineGenerator.generate(
            service("EchoBidiService", "ChatEcho", StreamingKind.BIDI),
            dataPackage,
        ).toString()

        assertThat(spec).contains("fun chatEcho(echoRequest: Flow<EchoRequest>): Flow<EchoResponse>")
        assertThat(spec).doesNotContain("suspend fun chatEcho")
        assertThat(spec).contains("ClientCalls.bidiStreamingRpc(")
        assertThat(spec).contains("ServerCalls.bidiStreamingServerMethodDefinition(")
        assertThat(spec).contains("MethodDescriptor.MethodType.BIDI_STREAMING")
    }

    @Test
    fun `kotlinPackage defaults to dataPackage`() {
        val spec = GrpcCoroutineGenerator.generate(
            service("EchoService", "Echo", StreamingKind.UNARY),
            dataPackage,
        )
        assertThat(spec.packageName).isEqualTo(dataPackage)
    }

    @Test
    fun `kotlinPackage can be overridden independently of dataPackage`() {
        val spec = GrpcCoroutineGenerator.generate(
            service("EchoService", "Echo", StreamingKind.UNARY),
            dataPackage,
            kotlinPackage = "com.example.echo.coroutines",
        )
        assertThat(spec.packageName).isEqualTo("com.example.echo.coroutines")
        assertThat(spec.toString()).contains("import com.example.echo.EchoRequest")
    }
}
