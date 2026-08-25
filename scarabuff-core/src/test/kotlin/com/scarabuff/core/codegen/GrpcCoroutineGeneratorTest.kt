package com.scarabuff.core.codegen

import com.google.common.truth.Truth.assertThat
import com.scarabuff.core.fbs.RpcMethod
import com.scarabuff.core.fbs.RpcServiceDef
import com.scarabuff.core.fbs.StreamingKind
import org.junit.jupiter.api.Test

class GrpcCoroutineGeneratorTest {

    private val javaPackage = "com.example.echo"

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
            javaPackage,
        ).toString()

        assertThat(spec).contains("object EchoServiceGrpcKt")
        assertThat(spec).contains("class EchoServiceCoroutineStub")
        assertThat(spec).contains("abstract class EchoServiceCoroutineImplBase")
        assertThat(spec).contains("suspend fun echo(echoRequest: EchoRequest): EchoResponse")
        assertThat(spec).contains("ClientCalls.unaryRpc(")
        assertThat(spec).contains("ServerCalls.unaryServerMethodDefinition(")
    }

    @Test
    fun `unary method descriptor accessor matches flatc naming`() {
        val spec = GrpcCoroutineGenerator.generate(
            service("EchoService", "Echo", StreamingKind.UNARY),
            javaPackage,
        ).toString()
        assertThat(spec).contains("getEchoMethod")
        assertThat(spec).contains("EchoServiceGrpc.getEchoMethod()")
    }

    @Test
    fun `server streaming returns Flow from a non-suspend stub method`() {
        val spec = GrpcCoroutineGenerator.generate(
            service("EchoStreamService", "ObserveEcho", StreamingKind.SERVER),
            javaPackage,
        ).toString()

        assertThat(spec).contains("fun observeEcho(echoRequest: EchoRequest): Flow<EchoResponse>")
        assertThat(spec).doesNotContain("suspend fun observeEcho")
        assertThat(spec).contains("ClientCalls.serverStreamingRpc(")
        assertThat(spec).contains("ServerCalls.serverStreamingServerMethodDefinition(")
    }

    @Test
    fun `client streaming takes a Flow request and is suspend`() {
        val spec = GrpcCoroutineGenerator.generate(
            service("EchoClientService", "CollectEcho", StreamingKind.CLIENT),
            javaPackage,
        ).toString()

        assertThat(spec).contains("suspend fun collectEcho(echoRequest: Flow<EchoRequest>): EchoResponse")
        assertThat(spec).contains("ClientCalls.clientStreamingRpc(")
        assertThat(spec).contains("ServerCalls.clientStreamingServerMethodDefinition(")
    }

    @Test
    fun `bidi streaming takes and returns Flow, non-suspend`() {
        val spec = GrpcCoroutineGenerator.generate(
            service("EchoBidiService", "ChatEcho", StreamingKind.BIDI),
            javaPackage,
        ).toString()

        assertThat(spec).contains("fun chatEcho(echoRequest: Flow<EchoRequest>): Flow<EchoResponse>")
        assertThat(spec).doesNotContain("suspend fun chatEcho")
        assertThat(spec).contains("ClientCalls.bidiStreamingRpc(")
        assertThat(spec).contains("ServerCalls.bidiStreamingServerMethodDefinition(")
    }

    @Test
    fun `kotlinPackage defaults to javaPackage`() {
        val spec = GrpcCoroutineGenerator.generate(
            service("EchoService", "Echo", StreamingKind.UNARY),
            javaPackage,
        )
        assertThat(spec.packageName).isEqualTo(javaPackage)
    }

    @Test
    fun `kotlinPackage can be overridden independently of javaPackage`() {
        val spec = GrpcCoroutineGenerator.generate(
            service("EchoService", "Echo", StreamingKind.UNARY),
            javaPackage,
            kotlinPackage = "com.example.echo.coroutines",
        )
        assertThat(spec.packageName).isEqualTo("com.example.echo.coroutines")
        assertThat(spec.toString()).contains("import com.example.echo.EchoRequest")
    }
}
