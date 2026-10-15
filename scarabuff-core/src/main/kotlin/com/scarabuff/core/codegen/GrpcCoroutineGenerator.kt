package com.scarabuff.core.codegen

import com.scarabuff.core.fbs.FbsTypeName
import com.scarabuff.core.fbs.RpcMethod
import com.scarabuff.core.fbs.RpcServiceDef
import com.scarabuff.core.fbs.StreamingKind
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec

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
 * The marshaller construction mirrors what flatc's Java `--grpc` output does via
 * `com.google.flatbuffers.grpc.FlatbuffersUtils.marshaller`, which is generic over any
 * `com.google.flatbuffers.Table` subclass — flatc's `--kotlin` output classes extend that same
 * base class, so it works unchanged against them.
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
    private val BYTE_BUFFER = ClassName("java.nio", "ByteBuffer")
    private val FLATBUFFERS_UTILS = ClassName("com.google.flatbuffers.grpc", "FlatbuffersUtils")
    private val FB_EXTRACTOR = ClassName("com.google.flatbuffers.grpc", "FlatbuffersUtils", "FBExtactor")

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
     */
    fun generate(
        service: RpcServiceDef,
        kotlinPackage: String = service.namespace,
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
        objectBuilder.addType(buildImplBaseClass(service))

        return FileSpec.builder(kotlinPackage, objectName)
            .addType(objectBuilder.build())
            .build()
    }

    private fun lazyDelegate(body: CodeBlock): CodeBlock =
        CodeBlock.builder()
            .add("lazy {\n")
            .indent()
            .add(body)
            .unindent()
            .add("}")
            .build()

    private fun extractorExpression(typeClass: ClassName): TypeSpec =
        TypeSpec.anonymousClassBuilder()
            .superclass(FB_EXTRACTOR.parameterizedBy(typeClass))
            .addFunction(
                FunSpec.builder("extract")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("buffer", BYTE_BUFFER)
                    .returns(typeClass)
                    .addStatement("return %T.getRootAs%L(buffer)", typeClass, typeClass.simpleName)
                    .build()
            )
            .build()

    private fun marshallerExpression(typeClass: ClassName): CodeBlock =
        CodeBlock.of(
            "%T.marshaller(%T::class.java, %L)",
            FLATBUFFERS_UTILS, typeClass, extractorExpression(typeClass),
        )

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

    private fun buildImplBaseClass(service: RpcServiceDef): TypeSpec {
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
        for (method in service.methods) {
            val strategy = STRATEGIES.getValue(method.streaming)
            bindServiceCode
                .add("\n.addMethod(\n")
                .indent()
                .add("%T.%L(\n", SERVER_CALLS, strategy.serverBindFunction)
                .indent()
                .add("context = this.context,\n")
                .add("descriptor = get%LMethod,\n", method.name)
                .add("implementation = ::%L\n", decapitalize(method.name))
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
     * Named for the call shape rather than the request type: a type-derived name like `channel`
     * (for a table called `Channel`) would shadow the stub's own `channel` property.
     */
    private fun requestParamName(strategy: StreamingStrategy): String =
        if (strategy.requestIsFlow) "requests" else "request"

    private fun FbsTypeName.toClassName(): ClassName = ClassName(packageName, simpleName)

    private fun decapitalize(s: String): String = s.replaceFirstChar { it.lowercaseChar() }
}
