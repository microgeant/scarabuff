package com.scarabuff.core

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Runs the real flatc (`$FLATC`, else `flatc` on `PATH`; `nix develop` provides the pinned one),
 * since what's under test is how scarabuff reads flatc's own view of the schema.
 */
class OrchestratorTest {

    @TempDir
    lateinit var tempDir: File

    private val flatc = System.getenv("FLATC") ?: "flatc"
    private val schemaDir get() = tempDir.resolve("fbs").apply { mkdirs() }
    private val outputDir get() = tempDir.resolve("out")

    private fun schema(name: String, text: String) = schemaDir.resolve(name).writeText(text.trimIndent())

    private fun generate() = Orchestrator.generate(schemaDir, outputDir, flatc)

    private fun ScarabuffResult.serviceFiles() =
        generatedKotlinFiles.map { it.name }.filter { it.endsWith("GrpcKt.kt") }

    private fun generated(qualifiedObject: String): String =
        outputDir.resolve(qualifiedObject.replace('.', '/') + ".kt").readText()

    @Test
    fun `types from an included file in another namespace resolve to their own package`() {
        schema(
            "common.fbs",
            """
            namespace com.probe.common;
            table Ping { msg:string; }
            """,
        )
        schema(
            "svc.fbs",
            """
            include "common.fbs";
            namespace com.probe.svc;
            table Pong { msg:string; }
            rpc_service ProbeService {
              Qualified(com.probe.common.Ping):Pong;
              Relative(common.Ping):Pong;
            }
            """,
        )

        generate()

        val code = generated("com.probe.svc.ProbeServiceGrpcKt")
        assertThat(code).contains("import com.probe.common.Ping")
        assertThat(code).contains("val getQualifiedMethod: MethodDescriptor<Ping, Pong>")
        assertThat(code).contains("val getRelativeMethod: MethodDescriptor<Ping, Pong>")
        assertThat(code).contains("\"com.probe.svc.ProbeService\"")
    }

    @Test
    fun `attributes besides streaming are accepted`() {
        schema(
            "attrs.fbs",
            """
            namespace com.probe.attrs;
            table Req { x:int; }
            table Resp { y:int; }
            rpc_service AttrService {
              Get(Req):Resp (idempotent);
              Watch(Req):Resp (streaming: "server", idempotent);
              Put(Req):Resp (idempotent, streaming: "client");
              Plain(Req):Resp (streaming: "none");
            }
            """,
        )

        generate()

        val code = generated("com.probe.attrs.AttrServiceGrpcKt")
        assertThat(code).contains("suspend fun `get`(request: Req")
        assertThat(code).contains("fun watch(request: Req")
        assertThat(code).contains("MethodDescriptor.MethodType.SERVER_STREAMING")
        assertThat(code).contains("suspend fun put(requests: Flow<Req>")
        assertThat(code).contains("suspend fun plain(request: Req")
    }

    @Test
    fun `a service in an included file is generated once`() {
        schema(
            "base.fbs",
            """
            namespace com.probe.base;
            table Req { x:int; }
            rpc_service BaseService { Call(Req):Req; }
            """,
        )
        schema(
            "ext.fbs",
            """
            include "base.fbs";
            namespace com.probe.ext;
            rpc_service ExtService { Call(com.probe.base.Req):com.probe.base.Req; }
            """,
        )

        val result = generate()

        assertThat(result.serviceFiles()).containsExactly("BaseServiceGrpcKt.kt", "ExtServiceGrpcKt.kt")
    }

    @Test
    fun `comments that look like schema are ignored`() {
        schema(
            "comments.fbs",
            """
            namespace com.probe.comments;
            table Req { x:int; }
            // rpc_service Fake { Nope(Req):Req; }
            /* rpc_service AlsoFake { Nope(Req):Req; } */
            /// Documented service
            rpc_service RealService {
              Call(Req):Req; // trailing comment with a } brace
            }
            """,
        )

        val result = generate()

        assertThat(result.serviceFiles()).containsExactly("RealServiceGrpcKt.kt")
    }

    @Test
    fun `invalid schema fails with flatc's error`() {
        schema(
            "bad.fbs",
            """
            namespace com.probe.bad;
            rpc_service BadService { Call(Missing):Missing; }
            """,
        )

        val e = assertThrows<IllegalStateException> { generate() }

        assertThat(e).hasMessageThat().contains("flatc failed")
        assertThat(e).hasMessageThat().contains("Missing")
    }

    @Test
    fun `every table in the schema directory gets a builders file, and structs don't`() {
        schema(
            "msgs.fbs",
            """
            namespace com.probe.msgs;
            struct Vec2 { x:float; y:float; }
            table User { id:uint; name:string (required); }
            table Post { author:User; tags:[string]; }
            """,
        )

        val result = generate()

        assertThat(result.generatedKotlinFiles.map { it.name }).containsExactly("UserBuilders.kt", "PostBuilders.kt")
        val user = generated("com.probe.msgs.UserBuilders")
        assertThat(user).contains("fun User.Companion.build(id: UInt = 0u, name: String): User")
        assertThat(user).contains("fun User.Companion.fromBytes(bytes: ByteArray): User")
    }

    @Test
    fun `tables with unsupported fields get fromBytes but no builders, with the reason reported`() {
        schema(
            "u.fbs",
            """
            namespace com.probe.u;
            struct Vec2 { x:float; y:float; }
            table A { x:int; }
            union Payload { A }
            table WithUnion { payload:Payload; }
            table WithStruct { pos:Vec2; }
            """,
        )

        val result = generate()

        assertThat(result.tablesWithoutBuilders).containsExactly(
            "com.probe.u.WithUnion", "union field `payload`",
            "com.probe.u.WithStruct", "struct field `pos`",
        )
        val withUnion = generated("com.probe.u.WithUnionBuilders")
        assertThat(withUnion).contains("fun WithUnion.Companion.fromBytes(")
        assertThat(withUnion).doesNotContain("fun WithUnion.Companion.build(")
    }

    @Test
    fun `tables from an include outside the schema directory get no builders`() {
        tempDir.resolve("shared").mkdirs()
        tempDir.resolve("shared/shared.fbs").writeText("namespace com.probe.shared;\ntable Shared { x:int; }\n")
        schema(
            "local.fbs",
            """
            include "../shared/shared.fbs";
            namespace com.probe.local;
            table Local { y:int; }
            """,
        )

        val result = generate()

        assertThat(result.generatedKotlinFiles.map { it.name }).containsExactly("LocalBuilders.kt")
    }

    @Test
    fun `builders can be turned off`() {
        schema("off.fbs", "namespace com.probe.off; table Off { x:int; }")

        val result = Orchestrator.generate(schemaDir, outputDir, flatc, generateBuilders = false)

        assertThat(result.generatedKotlinFiles).isEmpty()
    }
}
