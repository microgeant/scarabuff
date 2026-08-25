package com.scarabuff.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import javax.inject.Inject

/**
 * Configures the `scarabuff { ... }` block. Applied by [ScarabuffPlugin].
 */
abstract class ScarabuffExtension @Inject constructor(
    objects: ObjectFactory,
    providers: ProviderFactory,
) {
    /** Directory containing `.fbs` schemas. Defaults to `src/main/fbs`. */
    val schemaDir: DirectoryProperty = objects.directoryProperty()

    /** Where generated Java (`<dir>/java`) and Kotlin (`<dir>/kotlin`) sources land. */
    val outputDir: DirectoryProperty = objects.directoryProperty()

    /**
     * Path to (or name on `$PATH` of) the `flatc` executable. Defaults to the `$FLATC`
     * environment variable set by this project's `flake.nix` devShell, falling back to `flatc`
     * on `$PATH` when not running inside that shell.
     */
    val flatc: Property<String> = objects.property(String::class.java)
        .convention(providers.environmentVariable("FLATC").orElse("flatc"))

    /**
     * Appended to each schema's `namespace` to form the package generated `<Service>GrpcKt.kt`
     * files are written into. Empty (default) colocates them with the flatc-generated Java
     * classes they wrap; pass e.g. `.coroutines` to put them in a subpackage instead.
     */
    val kotlinPackageSuffix: Property<String> = objects.property(String::class.java).convention("")
}
