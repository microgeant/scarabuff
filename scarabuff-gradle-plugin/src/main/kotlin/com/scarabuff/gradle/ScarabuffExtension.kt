package com.scarabuff.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Configures the `scarabuff { ... }` block. Applied by [ScarabuffPlugin].
 */
abstract class ScarabuffExtension @Inject constructor(
    objects: ObjectFactory,
) {
    /** Directory containing `.fbs` schemas. Defaults to `src/main/fbs`. */
    val schemaDir: DirectoryProperty = objects.directoryProperty()

    /** Where generated Kotlin sources land. */
    val outputDir: DirectoryProperty = objects.directoryProperty()

    /**
     * The FlatBuffers version: the `flatbuffers-java` that [addRuntimeDependencies] adds, and so
     * the flatc scarabuff installs to match it. Defaults to the newest version scarabuff supports.
     * Set it to use another supported one (see the README's list); a newer `flatbuffers-java`
     * declared elsewhere still wins, as Gradle resolves to the highest version.
     */
    val flatbuffersVersion: Property<String> = objects.property(String::class.java)

    /**
     * FlatBuffers version of the flatc that scarabuff downloads, verifies and caches. Defaults to
     * the version of `com.google.flatbuffers:flatbuffers-java` on the compile classpath, so the
     * compiler always matches the runtime. Ignored when [flatc] or `$FLATC` is set.
     */
    val flatcVersion: Property<String> = objects.property(String::class.java)

    /**
     * Path to (or name on `$PATH` of) a flatc executable to use instead of the managed one.
     * Defaults to the `$FLATC` environment variable, when set.
     */
    val flatc: Property<String> = objects.property(String::class.java)

    /**
     * Adds the libraries the generated code compiles against (flatbuffers-java, grpc-stub, grpc-kotlin-stub, kotlinx-coroutines-core) to `api`, at
     * the versions scarabuff is tested with (flatbuffers-java at [flatbuffersVersion]). Defaults to `true`. Your own declarations still win
     * if they're newer; set to `false` to manage all of them yourself. A gRPC transport
     * (`grpc-netty-shaded`, `grpc-okhttp`, ...) is never added: that choice depends on the platform.
     */
    val addRuntimeDependencies: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

    /**
     * Generates `<Table>Builders.kt` for every table: `build(...)`, `buildBytes(...)` and
     * `fromBytes(...)` extension functions (plus `pack`/`copyInto` for composing messages by hand).
     * Defaults to `true`.
     */
    val generateBuilders: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

    /**
     * Appended to each schema's `namespace` to form the package generated `<Service>GrpcKt.kt`
     * files are written into. Empty (default) colocates them with the flatc-generated table
     * classes they wrap; pass e.g. `.coroutines` to put them in a subpackage instead.
     */
    val kotlinPackageSuffix: Property<String> = objects.property(String::class.java).convention("")
}
