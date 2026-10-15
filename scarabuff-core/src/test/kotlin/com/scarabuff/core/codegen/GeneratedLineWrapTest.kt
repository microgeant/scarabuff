package com.scarabuff.core.codegen

import com.google.common.truth.Truth.assertWithMessage
import com.scarabuff.core.fbs.FbsTypeName
import com.scarabuff.core.fbs.FieldDef
import com.scarabuff.core.fbs.FieldKind
import com.scarabuff.core.fbs.RpcMethod
import com.scarabuff.core.fbs.RpcServiceDef
import com.scarabuff.core.fbs.ScalarType
import com.scarabuff.core.fbs.StreamingKind
import com.scarabuff.core.fbs.TableDef
import org.junit.jupiter.api.Test

/**
 * KotlinPoet wraps long lines at ordinary spaces, and some breaks don't compile: one before a
 * lambda's `{` (`forEach\n{ s -> ... }`), or between a lambda parameter and `->`. Where the wrap
 * lands depends on name lengths, so this sweeps them rather than relying on one schema.
 */
class GeneratedLineWrapTest {

    private val lengths = 1..120

    private fun name(length: Int) = "f" + "x".repeat(length - 1)

    /**
     * A line break before a class or function body's `{` is valid Kotlin; one before a lambda's `{`
     * is not, because the lambda is then read as a separate expression. So a `{` may only start a
     * line if the line before doesn't end in one of the lambda-taking calls the generators emit.
     */
    private val lambdaCall = Regex("""(\blet|\bforEach|\blazy|\bIntArray\([^()]*\)|\bList\([^()]*\))$""")

    private fun assertWrapsSafely(code: String, context: String) {
        val lines = code.lines()
        lines.forEachIndexed { i, line ->
            val trimmed = line.trimStart()
            val previous = lines.getOrElse(i - 1) { "" }.trimEnd()
            assertWithMessage("$context, line ${i + 1} moves a lambda's brace onto its own line:\n$previous\n$line")
                .that(trimmed.startsWith("{") && lambdaCall.containsMatchIn(previous))
                .isFalse()
            assertWithMessage("$context, line ${i + 1} separates a lambda parameter from its arrow:\n$previous\n$line")
                .that(trimmed.startsWith("->"))
                .isFalse()
        }
    }

    @Test
    fun `table builders wrap safely whatever the name lengths`() {
        val pkg = "com.example.wrap"
        val struct = FbsTypeName(pkg, "NumItem")
        val nested = FbsTypeName(pkg, "Nested")
        val kinds = listOf(
            FieldKind.StructVector(struct),
            FieldKind.TableVector(nested),
            FieldKind.StrVector,
            FieldKind.ScalarVector(FieldKind.Scalar(ScalarType.UBYTE)),
            FieldKind.Struct(struct),
            FieldKind.Table(nested),
            FieldKind.Str,
            FieldKind.Scalar(ScalarType.ULONG),
        )
        for (length in lengths) {
            for (kind in kinds) {
                for (required in listOf(false, true)) {
                    val field = FieldDef(
                        name = name(length),
                        id = 7,
                        kind = kind,
                        required = required && kind !is FieldKind.Scalar,
                        optionalScalar = kind is FieldKind.Scalar,
                        defaultValue = null,
                    )
                    val table = TableDef(FbsTypeName(pkg, "Num" + "Payload".repeat(1 + length / 40)), listOf(field), null, null)
                    assertWrapsSafely(
                        TableBuilderGenerator.generate(table).toString(),
                        "field of length $length, $kind, required=$required",
                    )
                }
            }
        }
    }

    @Test
    fun `grpc stubs wrap safely whatever the name lengths`() {
        for (length in lengths) {
            val type = FbsTypeName("com.example.wrap", "T" + "y".repeat(length - 1))
            val service = RpcServiceDef(
                namespace = "com.example.wrap",
                name = "S" + "v".repeat(length - 1),
                methods = StreamingKind.entries.mapIndexed { i, kind -> RpcMethod("M$i" + "m".repeat(length), type, type, kind) },
            )
            assertWrapsSafely(GrpcCoroutineGenerator.generate(service).toString(), "names of length $length")
        }
    }
}
