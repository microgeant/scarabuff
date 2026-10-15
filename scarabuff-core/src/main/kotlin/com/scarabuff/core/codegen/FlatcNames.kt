package com.scarabuff.core.codegen

import com.scarabuff.core.fbs.FbsTypeName
import com.scarabuff.core.fbs.ScalarType
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.BYTE
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FLOAT
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.SHORT
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.U_BYTE
import com.squareup.kotlinpoet.U_INT
import com.squareup.kotlinpoet.U_LONG
import com.squareup.kotlinpoet.U_SHORT

/** How flatc's `--kotlin` output names things, for code that calls into it. */
internal object FlatcNames {

    /** Kotlin's hard keywords; flatc appends `_` to field names that collide with them. */
    private val KOTLIN_KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true",
        "try", "typealias", "typeof", "val", "var", "when", "while",
    )

    /** A schema field name in lowerCamelCase: `created_at` becomes `createdAt`. */
    fun camel(fieldName: String): String {
        val parts = fieldName.split('_').filter { it.isNotEmpty() }
        return parts.first().replaceFirstChar { it.lowercaseChar() } +
            parts.drop(1).joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }
    }

    /**
     * The name flatc uses for a field's accessor, `add` function and length: lowerCamelCase, with
     * `_` appended to Kotlin keywords (`object` becomes `object_`).
     */
    fun accessor(fieldName: String): String = camel(fieldName).let { if (it in KOTLIN_KEYWORDS) "${it}_" else it }
}

internal fun ScalarType.typeName(): TypeName = when (this) {
    ScalarType.BOOL -> BOOLEAN
    ScalarType.BYTE -> BYTE
    ScalarType.UBYTE -> U_BYTE
    ScalarType.SHORT -> SHORT
    ScalarType.USHORT -> U_SHORT
    ScalarType.INT -> INT
    ScalarType.UINT -> U_INT
    ScalarType.LONG -> LONG
    ScalarType.ULONG -> U_LONG
    ScalarType.FLOAT -> FLOAT
    ScalarType.DOUBLE -> DOUBLE
}

internal fun FbsTypeName.toClassName() = ClassName(packageName, simpleName)

internal fun ClassName.companion() = nestedClass("Companion")
