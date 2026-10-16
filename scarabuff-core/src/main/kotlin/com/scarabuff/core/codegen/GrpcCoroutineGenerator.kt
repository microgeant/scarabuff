package com.scarabuff.core.codegen

import com.scarabuff.core.fbs.FbsTypeName
import com.scarabuff.core.fbs.RpcMethod
import com.scarabuff.core.fbs.RpcServiceDef
import com.scarabuff.core.fbs.StreamingKind
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BYTE_ARRAY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName

/**
 * Generates a self-contained `<Service>GrpcKt` coroutine wrapper object for a single
 * `rpc_service` — matching the standard hand-written coroutine-stub pattern (`serviceDescriptor`,
 * one `MethodDescriptor` property per RPC, a `CoroutineStub` client class, and an abstract
 * `CoroutineImplBase` server class) but built entirely from Kotlin: the `ServiceDescriptor` and
 * `MethodDescriptor`s (including their FlatBuffers marshallers) are constructed here rather than
 * delegated to a flatc-generated Java `<Service>Grpc` class, since flatc's `--grpc` generator has
 * no Kotlin backend. This is what makes it possible to point flatc at `--kotlin` instead of
 * `--java` and get a build with no generated `.java` files at all.
 *
 * Each file also gets its own private marshaller for FlatBuffers tables (see [marshallerClass]),
 * rather than `flatbuffers-java-grpc`'s `FlatbuffersUtils.marshaller`, which ignores a buffer's
 * array offset, fails on direct buffers, and assumes one `read` returns a whole message.
 */
object GrpcCoroutineGenerator {

    private val CALL_OPTIONS = ClassName("io.grpc", "CallOptions")
    private val CHANNEL = ClassName("io.grpc", "Channel")
    private val METADATA = ClassName("io.grpc", "Metadata")
    private val METHOD_DESCRIPTOR = ClassName("io.grpc", "MethodDescriptor")
    private val METHOD_TYPE = ClassName("io.grpc", "MethodDescriptor", "MethodType")
    private val SERVER_SERVICE_DEFINITION = ClassName("io.grpc", "ServerServiceDefinition")
    private val SERVICE_DESCRIPTOR = ClassName("io.grpc", "ServiceDescriptor")
    private val STATUS = ClassName("io.grpc", "Status")
    private val STATUS_EXCEPTION = ClassName("io.grpc", "StatusException")
    private val ABSTRACT_COROUTINE_SERVER_IMPL = ClassName("io.grpc.kotlin", "AbstractCoroutineServerImpl")
    private val ABSTRACT_COROUTINE_STUB = ClassName("io.grpc.kotlin", "AbstractCoroutineStub")
    private val CLIENT_CALLS = ClassName("io.grpc.kotlin", "ClientCalls")
    private val SERVER_CALLS = ClassName("io.grpc.kotlin", "ServerCalls")
    private val STUB_FOR = ClassName("io.grpc.kotlin", "StubFor")
    private val COROUTINE_CONTEXT = ClassName("kotlin.coroutines", "CoroutineContext")
    private val EMPTY_COROUTINE_CONTEXT = ClassName("kotlin.coroutines", "EmptyCoroutineContext")
    private val FLOW = ClassName("kotlinx.coroutines.flow", "Flow")
    private val FLOW_MAP = MemberName("kotlinx.coroutines.flow", "map")
    private val BYTE_BUFFER = ClassName("java.nio", "ByteBuffer")
    private val TABLE = ClassName("com.google.flatbuffers", "Table")
    private val INPUT_STREAM = ClassName("java.io", "InputStream")
    private val OUTPUT_STREAM = ClassName("java.io", "OutputStream")
    private val DRAINABLE = ClassName("io.grpc", "Drainable")
    private val KNOWN_LENGTH = ClassName("io.grpc", "KnownLength")
    private const val MARSHALLER = "TableMarshaller"
    private const val TABLE_STREAM = "TableStream"

    private data class StreamingStrategy(
        val clientCallFunction: String,
        val serverBindFunction: String,
        val methodType: String,
        val isSuspend: Boolean,
        val requestIsFlow: Boolean,
        val responseIsFlow: Boolean,
    )

    private val STRATEGIES: Map<StreamingKind, StreamingStrategy> = mapOf(
        StreamingKind.UNARY to StreamingStrategy(
            clientCallFunction = "unaryRpc",
            serverBindFunction = "unaryServerMethodDefinition",
            methodType = "UNARY",
            isSuspend = true,
            requestIsFlow = false,
            responseIsFlow = false,
        ),
        StreamingKind.SERVER to StreamingStrategy(
            clientCallFunction = "serverStreamingRpc",
            serverBindFunction = "serverStreamingServerMethodDefinition",
            methodType = "SERVER_STREAMING",
            isSuspend = false,
            requestIsFlow = false,
            responseIsFlow = true,
        ),
        StreamingKind.CLIENT to StreamingStrategy(
            clientCallFunction = "clientStreamingRpc",
            serverBindFunction = "clientStreamingServerMethodDefinition",
            methodType = "CLIENT_STREAMING",
            isSuspend = true,
            requestIsFlow = true,
            responseIsFlow = false,
        ),
        StreamingKind.BIDI to StreamingStrategy(
            clientCallFunction = "bidiStreamingRpc",
            serverBindFunction = "bidiStreamingServerMethodDefinition",
            methodType = "BIDI_STREAMING",
            isSuspend = false,
            requestIsFlow = true,
            responseIsFlow = true,
        ),
    )

    /**
     * @param kotlinPackage the package to emit the generated `<Service>GrpcKt` file into; defaults
     *   to the service's namespace, which is where flatc's `--kotlin` output puts the table
     *   classes declared alongside it
     * @param withRequired request types with required fields, directly or nested; the server checks
     *   these before calling the implementation, using the generated `missingRequiredField()`
     */
    fun generate(
        service: RpcServiceDef,
        kotlinPackage: String = service.namespace,
        withRequired: Set<FbsTypeName> = emptySet(),
    ): FileSpec {
        val objectName = "${service.name}GrpcKt"
        val serviceNameConst = service.qualifiedName

        val objectBuilder = TypeSpec.objectBuilder(objectName)
            .addProperty(
                PropertySpec.builder("SERVICE_NAME", String::class)
                    .addModifiers(KModifier.PRIVATE, KModifier.CONST)
                    .initializer("%S", serviceNameConst)
                    .build()
            )

        for (method in service.methods) {
            objectBuilder.addProperty(methodDescriptorProperty(method))
        }
        objectBuilder.addProperty(serviceDescriptorProperty(service))

        objectBuilder.addType(buildStubClass(service))
        objectBuilder.addType(buildImplBaseClass(service, withRequired))
        objectBuilder.addType(marshallerClass())
        objectBuilder.addType(tableStreamClass())

        return FileSpec.builder(kotlinPackage, objectName)
            .addType(objectBuilder.build())
            .build()
    }

    private fun lazyDelegate(body: CodeBlock): CodeBlock =
        CodeBlock.builder()
            // `·` is KotlinPoet's non-breaking space: a line break between `lazy` and `{` won't compile.
            .add("lazy·{\n")
            .indent()
            .add(body)
            .unindent()
            .add("}")
            .build()

    private fun marshallerExpression(typeClass: ClassName): CodeBlock =
        CodeBlock.of("%L·{ %T.getRootAs%L(it) }", MARSHALLER, typeClass, typeClass.simpleName)

    /**
     * Sends a table's bytes straight from its buffer's backing array, with no copy (grpc-java
     * drains the stream into the transport), honouring the buffer's array offset. A buffer without
     * an accessible array (a direct or read-only one) is copied once instead. Receives into one
     * array of the message's exact size, read in a loop: `InputStream.read` may return less than
     * asked for.
     */
    private fun marshallerClass(): TypeSpec {
        val t = TypeVariableName("T", TABLE)
        return TypeSpec.classBuilder(MARSHALLER)
            .addModifiers(KModifier.PRIVATE)
            .addTypeVariable(t)
            .addSuperinterface(METHOD_DESCRIPTOR.nestedClass("Marshaller").parameterizedBy(t))
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("root", LambdaTypeName.get(parameters = arrayOf(BYTE_BUFFER), returnType = t))
                    .build()
            )
            .addProperty(
                PropertySpec.builder("root", LambdaTypeName.get(parameters = arrayOf(BYTE_BUFFER), returnType = t))
                    .addModifiers(KModifier.PRIVATE).initializer("root").build()
            )
            .addFunction(
                FunSpec.builder("stream")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("value", t)
                    .returns(INPUT_STREAM)
                    .addStatement("val buffer = value.byteBuffer")
                    .beginControlFlow("if (buffer.hasArray())")
                    .addStatement(
                        "return %L(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining())",
                        TABLE_STREAM,
                    )
                    .endControlFlow()
                    .addStatement("val copy = ByteArray(buffer.remaining())")
                    .addStatement("buffer.duplicate().get(copy)")
                    .addStatement("return %L(copy, 0, copy.size)", TABLE_STREAM)
                    .build()
            )
            .addFunction(
                FunSpec.builder("parse")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("stream", INPUT_STREAM)
                    .returns(t)
                    .beginControlFlow("val bytes = if (stream is %T)", KNOWN_LENGTH)
                    .addStatement("val exact = ByteArray(stream.available())")
                    .addStatement("var read = 0")
                    .beginControlFlow("while (read < exact.size)")
                    .addStatement("val n = stream.read(exact, read, exact.size - read)")
                    .addStatement("if (n < 0) throw %T(\"message ended after \$read of \${exact.size} bytes\")", ClassName("java.io", "EOFException"))
                    .addStatement("read += n")
                    .endControlFlow()
                    .addStatement("exact")
                    .nextControlFlow("else")
                    .addStatement("stream.readBytes()")
                    .endControlFlow()
                    .addStatement("return root(%T.wrap(bytes))", BYTE_BUFFER)
                    .build()
            )
            .build()
    }

    /** A table's bytes for grpc-java: [drainTo] writes them to the transport without a copy. */
    private fun tableStreamClass(): TypeSpec =
        TypeSpec.classBuilder(TABLE_STREAM)
            .addModifiers(KModifier.PRIVATE)
            .superclass(INPUT_STREAM)
            .addSuperinterface(DRAINABLE)
            .addSuperinterface(KNOWN_LENGTH)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("bytes", BYTE_ARRAY)
                    .addParameter("offset", INT)
                    .addParameter("length", INT)
                    .build()
            )
            .addProperty(PropertySpec.builder("bytes", BYTE_ARRAY, KModifier.PRIVATE).initializer("bytes").build())
            .addProperty(PropertySpec.builder("position", INT, KModifier.PRIVATE).mutable().initializer("offset").build())
            .addProperty(PropertySpec.builder("end", INT, KModifier.PRIVATE).initializer("offset + length").build())
            .addFunction(
                FunSpec.builder("drainTo")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("target", OUTPUT_STREAM)
                    .returns(INT)
                    .addStatement("val n = end - position")
                    .addStatement("target.write(bytes, position, n)")
                    .addStatement("position = end")
                    .addStatement("return n")
                    .build()
            )
            .addFunction(
                FunSpec.builder("available").addModifiers(KModifier.OVERRIDE).returns(INT)
                    .addStatement("return end - position").build()
            )
            .addFunction(
                FunSpec.builder("read").addModifiers(KModifier.OVERRIDE).returns(INT)
                    .addStatement("return if (position < end) bytes[position++].toInt() and 0xff else -1").build()
            )
            .addFunction(
                FunSpec.builder("read").addModifiers(KModifier.OVERRIDE)
                    .addParameter("b", BYTE_ARRAY)
                    .addParameter("off", INT)
                    .addParameter("len", INT)
                    .returns(INT)
                    .addStatement("if (position >= end) return if (len == 0) 0 else -1")
                    .addStatement("val n = minOf(len, end - position)")
                    .addStatement("System.arraycopy(bytes, position, b, off, n)")
                    .addStatement("position += n")
                    .addStatement("return n")
                    .build()
            )
            .build()

    private fun methodDescriptorProperty(method: RpcMethod): PropertySpec {
        val strategy = STRATEGIES.getValue(method.streaming)
        val requestClass = method.requestType.toClassName()
        val responseClass = method.responseType.toClassName()
        val type = METHOD_DESCRIPTOR.parameterizedBy(requestClass, responseClass)

        val body = CodeBlock.builder()
            .add("%T.newBuilder<%T, %T>()\n", METHOD_DESCRIPTOR, requestClass, responseClass)
            .indent()
            .add(".setType(%T.%L)\n", METHOD_TYPE, strategy.methodType)
            .add(".setFullMethodName(%T.generateFullMethodName(SERVICE_NAME, %S))\n", METHOD_DESCRIPTOR, method.name)
            .add(".setSampledToLocalTracing(true)\n")
            .add(".setRequestMarshaller(%L)\n", marshallerExpression(requestClass))
            .add(".setResponseMarshaller(%L)\n", marshallerExpression(responseClass))
            .add(".build()\n")
            .unindent()
            .build()

        return PropertySpec.builder("get${method.name}Method", type)
            .addAnnotation(JvmStatic::class)
            .delegate(lazyDelegate(body))
            .build()
    }

    private fun serviceDescriptorProperty(service: RpcServiceDef): PropertySpec {
        val body = CodeBlock.builder()
            .add("%T.newBuilder(SERVICE_NAME)\n", SERVICE_DESCRIPTOR)
            .indent()
            .apply { for (method in service.methods) add(".addMethod(get%LMethod)\n", method.name) }
            .add(".build()\n")
            .unindent()
            .build()

        return PropertySpec.builder("serviceDescriptor", SERVICE_DESCRIPTOR)
            .addAnnotation(JvmStatic::class)
            .delegate(lazyDelegate(body))
            .build()
    }

    private fun buildStubClass(service: RpcServiceDef): TypeSpec {
        val stubClassName = "${service.name}CoroutineStub"
        val selfType = ClassName("", stubClassName)

        val constructor = FunSpec.constructorBuilder()
            .addAnnotation(JvmOverloads::class)
            .addParameter("channel", CHANNEL)
            .addParameter(
                ParameterSpec.builder("callOptions", CALL_OPTIONS)
                    .defaultValue("%T.DEFAULT", CALL_OPTIONS)
                    .build()
            )
            .build()

        val builder = TypeSpec.classBuilder(stubClassName)
            .addAnnotation(
                AnnotationSpec.builder(STUB_FOR)
                    .addMember("%L::class", "${service.name}GrpcKt")
                    .build()
            )
            .primaryConstructor(constructor)
            .superclass(ABSTRACT_COROUTINE_STUB.parameterizedBy(selfType))
            .addSuperclassConstructorParameter("channel")
            .addSuperclassConstructorParameter("callOptions")
            .addFunction(
                FunSpec.builder("build")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("channel", CHANNEL)
                    .addParameter("callOptions", CALL_OPTIONS)
                    .returns(selfType)
                    .addStatement("return %L(channel, callOptions)", stubClassName)
                    .build()
            )

        for (method in service.methods) {
            val strategy = STRATEGIES.getValue(method.streaming)
            val requestClass = method.requestType.toClassName()
            val responseClass = method.responseType.toClassName()
            val paramName = requestParamName(strategy)
            val paramType = if (strategy.requestIsFlow) FLOW.parameterizedBy(requestClass) else requestClass
            val returnType = if (strategy.responseIsFlow) FLOW.parameterizedBy(responseClass) else responseClass

            val fn = FunSpec.builder(decapitalize(method.name))
                .apply { if (strategy.isSuspend) addModifiers(KModifier.SUSPEND) }
                .addParameter(paramName, paramType)
                .addParameter(
                    ParameterSpec.builder("headers", METADATA)
                        .defaultValue("%T()", METADATA)
                        .build()
                )
                .returns(returnType)
                .addCode(
                    CodeBlock.builder()
                        .add("return %T.%L(\n", CLIENT_CALLS, strategy.clientCallFunction)
                        .indent()
                        .add("channel,\n")
                        .add("get%LMethod,\n", method.name)
                        .add("%L,\n", paramName)
                        .add("callOptions,\n")
                        .add("headers\n")
                        .unindent()
                        .add(")\n")
                        .build()
                )
                .build()
            builder.addFunction(fn)
        }

        return builder.build()
    }

    private fun buildImplBaseClass(service: RpcServiceDef, withRequired: Set<FbsTypeName>): TypeSpec {
        val implClassName = "${service.name}CoroutineImplBase"

        val constructor = FunSpec.constructorBuilder()
            .addParameter(
                ParameterSpec.builder("coroutineContext", COROUTINE_CONTEXT)
                    .defaultValue("%T", EMPTY_COROUTINE_CONTEXT)
                    .build()
            )
            .build()

        val builder = TypeSpec.classBuilder(implClassName)
            .addModifiers(KModifier.ABSTRACT)
            .primaryConstructor(constructor)
            .superclass(ABSTRACT_COROUTINE_SERVER_IMPL)
            .addSuperclassConstructorParameter("coroutineContext")

        for (method in service.methods) {
            val strategy = STRATEGIES.getValue(method.streaming)
            val requestClass = method.requestType.toClassName()
            val responseClass = method.responseType.toClassName()
            val paramName = requestParamName(strategy)
            val paramType = if (strategy.requestIsFlow) FLOW.parameterizedBy(requestClass) else requestClass
            val returnType = if (strategy.responseIsFlow) FLOW.parameterizedBy(responseClass) else responseClass

            val fn = FunSpec.builder(decapitalize(method.name))
                .addModifiers(KModifier.OPEN)
                .apply { if (strategy.isSuspend) addModifiers(KModifier.SUSPEND) }
                .addParameter(paramName, paramType)
                .returns(returnType)
                .addStatement(
                    "throw %T(%T.UNIMPLEMENTED.withDescription(%S))",
                    STATUS_EXCEPTION,
                    STATUS,
                    "METHOD UNIMPLEMENTED",
                )
                .build()
            builder.addFunction(fn)
        }

        val bindServiceCode = CodeBlock.builder()
            .add("return %T.builder(\n", SERVER_SERVICE_DEFINITION)
            .indent()
            .add("serviceDescriptor\n")
            .unindent()
            .add(")")
        // Request types checked for required fields: one private function each. The leading `_`
        // keeps it apart from the RPC methods, which schemas name in UpperCamelCase.
        val checked = service.methods.map { it.requestType }.filter { it in withRequired }.distinct()
        for (type in checked) builder.addFunction(checkRequired(type))

        for (method in service.methods) {
            val strategy = STRATEGIES.getValue(method.streaming)
            val name = decapitalize(method.name)
            val implementation = when {
                method.requestType !in withRequired -> CodeBlock.of("::%L", name)
                strategy.requestIsFlow -> CodeBlock.of("{·requests·-> %L(requests.%M·{ _checkRequired(it) }) }", name, FLOW_MAP)
                else -> CodeBlock.of("{·request·-> %L(_checkRequired(request)) }", name)
            }
            bindServiceCode
                .add("\n.addMethod(\n")
                .indent()
                .add("%T.%L(\n", SERVER_CALLS, strategy.serverBindFunction)
                .indent()
                .add("context = this.context,\n")
                .add("descriptor = get%LMethod,\n", method.name)
                .add("implementation = %L\n", implementation)
                .unindent()
                .add(")\n")
                .unindent()
                .add(")")
        }
        bindServiceCode.add("\n.build()\n")

        builder.addFunction(
            FunSpec.builder("bindService")
                .addModifiers(KModifier.FINAL, KModifier.OVERRIDE)
                .returns(SERVER_SERVICE_DEFINITION)
                .addCode(bindServiceCode.build())
                .build()
        )

        return builder.build()
    }

    /**
     * Fails the call with `INVALID_ARGUMENT` if a request lacks a required field, before the
     * implementation sees it. Otherwise reading that field would throw `AssertionError` in the
     * implementation, which reaches the client as `UNKNOWN`. grpc-kotlin turns a `StatusException`
     * thrown here, or while the implementation collects a request flow, into the call's status.
     */
    private fun checkRequired(type: FbsTypeName): FunSpec {
        val typeClass = type.toClassName()
        return FunSpec.builder("_checkRequired")
            .addModifiers(KModifier.PRIVATE)
            .addParameter("request", typeClass)
            .returns(typeClass)
            .addStatement("val missing = request.%M() ?: return·request", MemberName(type.packageName, "missingRequiredField"))
            .addStatement(
                "throw %T(%T.INVALID_ARGUMENT.withDescription(%S + missing))",
                STATUS_EXCEPTION, STATUS, "${type.simpleName} is missing required field ",
            )
            .build()
    }

    /**
     * Named for the call shape rather than the request type: a type-derived name like `channel`
     * (for a table called `Channel`) would shadow the stub's own `channel` property.
     */
    private fun requestParamName(strategy: StreamingStrategy): String =
        if (strategy.requestIsFlow) "requests" else "request"

    private fun FbsTypeName.toClassName(): ClassName = ClassName(packageName, simpleName)

    private fun decapitalize(s: String): String = s.replaceFirstChar { it.lowercaseChar() }
}
