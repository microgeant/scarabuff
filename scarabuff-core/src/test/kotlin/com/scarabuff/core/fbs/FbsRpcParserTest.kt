package com.scarabuff.core.fbs

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FbsRpcParserTest {

    /** Mirrors the structure of Protoloop's real clip.fbs: one service per streaming kind. */
    private val fourKindsSchema = """
        namespace com.example.echo;

        table EchoRequest { message:string; }
        table EchoResponse { message:string; }

        // Unary
        rpc_service EchoService {
          Echo(EchoRequest):EchoResponse;
        }

        // Server streaming
        rpc_service EchoStreamService {
          ObserveEcho(EchoRequest):EchoResponse (streaming: "server");
        }

        /* Client streaming, with a block comment right above it */
        rpc_service EchoClientService {
          CollectEcho(EchoRequest):EchoResponse (streaming: "client");
        }

        rpc_service EchoBidiService {
          ChatEcho(EchoRequest):EchoResponse (streaming: "bidi");
        }
    """.trimIndent()

    @Test
    fun `parses namespace`() {
        val schema = FbsRpcParser.parse(fourKindsSchema)
        assertThat(schema.namespace).isEqualTo("com.example.echo")
    }

    @Test
    fun `parses all four streaming kinds`() {
        val schema = FbsRpcParser.parse(fourKindsSchema)
        assertThat(schema.services).hasSize(4)

        val byServiceName = schema.services.associateBy { it.name }
        assertThat(byServiceName.getValue("EchoService").methods.single().streaming)
            .isEqualTo(StreamingKind.UNARY)
        assertThat(byServiceName.getValue("EchoStreamService").methods.single().streaming)
            .isEqualTo(StreamingKind.SERVER)
        assertThat(byServiceName.getValue("EchoClientService").methods.single().streaming)
            .isEqualTo(StreamingKind.CLIENT)
        assertThat(byServiceName.getValue("EchoBidiService").methods.single().streaming)
            .isEqualTo(StreamingKind.BIDI)
    }

    @Test
    fun `parses method request and response types`() {
        val schema = FbsRpcParser.parse(fourKindsSchema)
        val method = schema.services.first { it.name == "EchoService" }.methods.single()
        assertThat(method.name).isEqualTo("Echo")
        assertThat(method.requestType).isEqualTo("EchoRequest")
        assertThat(method.responseType).isEqualTo("EchoResponse")
    }

    @Test
    fun `schema with no rpc_service blocks parses to empty service list`() {
        val schema = FbsRpcParser.parse(
            """
            namespace com.example.noservices;
            table Foo { x:int; }
            """.trimIndent()
        )
        assertThat(schema.services).isEmpty()
    }

    @Test
    fun `service with multiple methods parses each independently`() {
        val schema = FbsRpcParser.parse(
            """
            namespace com.example.multi;
            table Req { x:int; }
            table Resp { y:int; }
            rpc_service MultiService {
              First(Req):Resp;
              Second(Req):Resp (streaming: "bidi");
            }
            """.trimIndent()
        )
        val methods = schema.services.single().methods
        assertThat(methods).hasSize(2)
        assertThat(methods[0].streaming).isEqualTo(StreamingKind.UNARY)
        assertThat(methods[1].streaming).isEqualTo(StreamingKind.BIDI)
    }

    @Test
    fun `missing namespace throws`() {
        try {
            FbsRpcParser.parse("table Foo { x:int; }")
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
