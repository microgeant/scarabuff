package com.scarabuff.core.codegen

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
 * Generates a `<Service>GrpcKt` coroutine wrapper object for a single `rpc_service`, matching
 * the standard hand-written coroutine-stub pattern:
 * a `serviceDescriptor`, one `MethodDescriptor` property per RPC, a `CoroutineStub` client class,
 * and an abstract `CoroutineImplBase` server class — with the exact `ClientCalls`/`ServerCalls`
 * function and suspend/Flow shape depending on the RPC's streaming kind.
 */
object GrpcCoroutineGenerator {

    private val CALL_OPTIONS = ClassName("io.grpc", "CallOptions")
    private val CHANNEL = ClassName("io.grpc", "Channel")
    private val METADATA = ClassName("io.grpc", "Metadata")
    private val METHOD_DESCRIPTOR = ClassName("io.grpc", "MethodDescriptor")
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

    private data class StreamingStrategy(
        val clientCallFunction: String,
        val serverBindFunction: String,
        val isSuspend: Boolean,
        val requestIsFlow: Boolean,
        val responseIsFlow: Boolean,
    )

    private val STRATEGIES: Map<StreamingKind, StreamingStrategy> = mapOf(
        StreamingKind.UNARY to StreamingStrategy(
            clientCallFunction = "unaryRpc",
            serverBindFunction = "unaryServerMethodDefinition",
            isSuspend = true,
            requestIsFlow = false,
            responseIsFlow = false,
        ),
        StreamingKind.SERVER to StreamingStrategy(
            clientCallFunction = "serverStreamingRpc",
            serverBindFunction = "serverStreamingServerMethodDefinition",
            isSuspend = false,
            requestIsFlow = false,
            responseIsFlow = true,
        ),
        StreamingKind.CLIENT to StreamingStrategy(
            clientCallFunction = "clientStreamingRpc",
            serverBindFunction = "clientStreamingServerMethodDefinition",
            isSuspend = true,
            requestIsFlow = true,
            responseIsFlow = false,
        ),
        StreamingKind.BIDI to StreamingStrategy(
            clientCallFunction = "bidiStreamingRpc",
            serverBindFunction = "bidiStreamingServerMethodDefinition",
            isSuspend = false,
            requestIsFlow = true,
            responseIsFlow = true,
        ),
    )

    /**
     * @param javaPackage the package flatc emitted `<Service>Grpc`/request/response classes into
     * @param kotlinPackage the package to emit the generated `<Service>GrpcKt` file into; defaults
     *   to [javaPackage] (colocate the coroutine wrapper with the generated Java stub it wraps)
     */
    fun generate(
        service: RpcServiceDef,
        javaPackage: String,
        kotlinPackage: String = javaPackage,
    ): FileSpec {
        val serviceGrpcClass = ClassName(javaPackage, "${service.name}Grpc")
        val objectName = "${service.name}GrpcKt"

        val objectBuilder = TypeSpec.objectBuilder(objectName)
            .addProperty(serviceDescriptorProperty(serviceGrpcClass))

        for (method in service.methods) {
            objectBuilder.addProperty(methodDescriptorProperty(method.name, javaPackage, serviceGrpcClass, method.requestType, method.responseType))
        }

        objectBuilder.addType(buildStubClass(service, serviceGrpcClass, javaPackage))
        objectBuilder.addType(buildImplBaseClass(service, serviceGrpcClass, javaPackage))

        return FileSpec.builder(kotlinPackage, objectName)
            .addType(objectBuilder.build())
            .build()
    }

    private fun serviceDescriptorProperty(serviceGrpcClass: ClassName): PropertySpec =
        PropertySpec.builder("serviceDescriptor", SERVICE_DESCRIPTOR)
            .getter(
                FunSpec.getterBuilder()
                    .addAnnotation(JvmStatic::class)
                    .addStatement("return %T.getServiceDescriptor()", serviceGrpcClass)
                    .build()
            )
            .build()

    private fun methodDescriptorProperty(
        methodName: String,
        javaPackage: String,
        serviceGrpcClass: ClassName,
        requestType: String,
        responseType: String,
    ): PropertySpec {
        val requestClass = ClassName(javaPackage, requestType)
        val responseClass = ClassName(javaPackage, responseType)
        val type = METHOD_DESCRIPTOR.parameterizedBy(requestClass, responseClass)
        return PropertySpec.builder("get${methodName}Method", type)
            .getter(
                FunSpec.getterBuilder()
                    .addAnnotation(JvmStatic::class)
                    .addStatement("return %T.get${methodName}Method()", serviceGrpcClass)
                    .build()
            )
            .build()
    }

    private fun buildStubClass(service: RpcServiceDef, serviceGrpcClass: ClassName, javaPackage: String): TypeSpec {
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
                    .addMember("%T::class", serviceGrpcClass)
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
            val requestClass = ClassName(javaPackage, method.requestType)
            val responseClass = ClassName(javaPackage, method.responseType)
            val paramName = decapitalize(method.requestType)
            val paramType = if (strategy.requestIsFlow) FLOW.parameterizedBy(requestClass) else requestClass
            val returnType = if (strategy.responseIsFlow) FLOW.parameterizedBy(responseClass) else responseClass

            val fn = FunSpec.builder(decapitalize(method.name))
                .apply { if (strategy.isSuspend) addModifiers(KModifier.SUSPEND) }
                .addParameter(paramName, paramType)
                .returns(returnType)
                .addCode(
                    CodeBlock.builder()
                        .add("return %T.%L(\n", CLIENT_CALLS, strategy.clientCallFunction)
                        .indent()
                        .add("channel,\n")
                        .add("get%LMethod,\n", method.name)
                        .add("%L,\n", paramName)
                        .add("callOptions,\n")
                        .add("%T()\n", METADATA)
                        .unindent()
                        .add(")\n")
                        .build()
                )
                .build()
            builder.addFunction(fn)
        }

        return builder.build()
    }

    private fun buildImplBaseClass(service: RpcServiceDef, serviceGrpcClass: ClassName, javaPackage: String): TypeSpec {
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
            val requestClass = ClassName(javaPackage, method.requestType)
            val responseClass = ClassName(javaPackage, method.responseType)
            val paramName = decapitalize(method.requestType)
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
            .add("%T.getServiceDescriptor()\n", serviceGrpcClass)
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

    private fun decapitalize(s: String): String = s.replaceFirstChar { it.lowercaseChar() }
}
