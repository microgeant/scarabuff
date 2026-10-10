# scarabuff

A code generator for FlatBuffers: point it at a
directory of `.fbs` files that define `rpc_service`s, and it generates the FlatBuffers table
classes (via `flatc --kotlin`) plus a `<Service>GrpcKt` object with a `serviceDescriptor`, one
`MethodDescriptor` per RPC, a `CoroutineStub` (client), and an abstract `CoroutineImplBase`
(server) — for all four gRPC shapes: unary, server-streaming, client-streaming, and
bidirectional-streaming. The output is pure Kotlin, no generated `.java` files: flatc's `--grpc`
generator has no Kotlin backend, so scarabuff builds the `ServiceDescriptor`/`MethodDescriptor`/
marshaller glue itself (see [Why it's pure Kotlin](#why-its-pure-kotlin)) instead of delegating to
a flatc-generated Java `<Service>Grpc` class the way a hand-rolled setup normally would.

It exists to delete hand-written boilerplate like this (one file per service, one per streaming
kind, ~50-80 nearly-identical lines each):

```kotlin
object ClipAudioServiceGrpcKt {
    val serviceDescriptor: ServiceDescriptor get() = ClipAudioServiceGrpc.getServiceDescriptor()
    val getClipAudioMethod: MethodDescriptor<ClipAudioRequest, ClipAudioResponse>
        get() = ClipAudioServiceGrpc.getGetClipAudioMethod()

    class ClipAudioServiceCoroutineStub(...) : AbstractCoroutineStub<...>(...) {
        suspend fun getClipAudio(clipAudioRequest: ClipAudioRequest): ClipAudioResponse =
            unaryRpc(channel, getClipAudioMethod, clipAudioRequest, callOptions, Metadata())
    }

    abstract class ClipAudioServiceCoroutineImplBase(...) : AbstractCoroutineServerImpl(...) {
        open suspend fun getClipAudio(...): ClipAudioResponse = throw StatusException(...)
        final override fun bindService(): ServerServiceDefinition = ...
    }
}
```

...down to just the `.fbs` schema that describes it:

```fbs
rpc_service ClipAudioService {
  GetClipAudio(ClipAudioRequest):ClipAudioResponse;
}
```

## Project layout

- **`scarabuff-core`** — the actual logic, as a plain JVM library with no Gradle API dependency:
  a text parser for `rpc_service` blocks (`FbsRpcParser`), a [KotlinPoet](https://square.github.io/kotlinpoet/)-based
  generator (`GrpcCoroutineGenerator`), and an `Orchestrator` that runs `flatc` and the generator
  together. Both front ends below call the same `Orchestrator.generate(...)`.
- **`scarabuff-gradle-plugin`** — applies a `scarabuff { ... }` extension and a
  `generateScarabuffSources` task, wired into the `main` Kotlin source set ahead of
  `compileKotlin`. This is the primary way to use scarabuff.
- **`scarabuff-cli`** — a thin standalone wrapper (`--schema-dir`, `--out-dir`, `--flatc`,
  `--kotlin-package-suffix`) for non-Gradle use, built as a fat jar via the shadow plugin.
- **`example/`** — a self-contained sample project (its own Gradle build, pulling the plugin in
  via `includeBuild("..")` so it always tests the working tree) with a 4-service `echo.fbs`
  (one service per streaming kind) and `EchoRoundTripTest.kt`, which starts a real in-process
  gRPC server and client using the generated stubs and asserts real round-trip responses for all
  four RPC kinds. This is the thing to run if you want proof it actually works, not just compiles.

## Why it's pure Kotlin

flatc's `--grpc` flag has no Kotlin implementation — running `flatc --kotlin --grpc` prints
`GRPC interface generator not implemented for Kotlin` and silently emits only the table classes,
nothing gRPC-related. Normally (e.g. for Java) `--grpc` is what generates the `<Service>Grpc`
class holding the `ServiceDescriptor`, one `MethodDescriptor` per RPC, and the FlatBuffers
marshallers that turn a `ByteBuffer` into a table instance and back — a hand-written coroutine
wrapper would then just delegate to that class's static accessors.

Since that class doesn't exist for Kotlin, `GrpcCoroutineGenerator` builds the same
`ServiceDescriptor`/`MethodDescriptor`/marshaller logic itself via KotlinPoet, targeting the
`--kotlin`-generated table classes directly. This works because those classes extend the exact
same `com.google.flatbuffers.Table` base class the Java ones do, and
`com.google.flatbuffers.grpc.FlatbuffersUtils.marshaller(...)` (from the `flatbuffers-java-grpc`
runtime jar, still a regular dependency — not generated code) is generic over any `Table`
subclass. So `Orchestrator` runs `flatc --kotlin` (no `--java`, no `--grpc`) and the entire
generated output — table classes and gRPC wrapper alike — is Kotlin.

## Version pinning

`flatc`'s compiler version and the `flatbuffers-java`/`flatbuffers-java-grpc` runtime jars **must
match** — FlatBuffers releases `flatc` and every language runtime together under one version
number, and a newer `flatc` will happily emit code the older runtime can't load (e.g. a
`Constants.FLATBUFFERS_25_12_19()` version-check call that doesn't exist in a `25.2.10` runtime
jar — this is a real failure this project hit and worked around during development).

- **JVM dependency versions** (grpc, grpc-kotlin, flatbuffers-java, kotlinx-coroutines, kotlin,
  kotlinpoet) are pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).
- **`flatc` itself** is pinned by [`flake.nix`](flake.nix): `nix develop` drops you into a shell
  with `flatc` built from the FlatBuffers `v25.2.10` GitHub release binaries (exact version match
  to the `flatbuffers-java` in the version catalog) on `$PATH`, with `$FLATC` exported. The
  Gradle plugin's `flatc` property defaults to `$FLATC`, falling back to `flatc` on `$PATH` when
  not running inside the devShell — so the pin only "takes" inside `nix develop`; outside it,
  you're on whatever `flatc` you have installed, which is exactly the hazard above.
- To bump versions: change `flatcVersion` in `flake.nix` (and its per-system asset hashes — see
  the comment there) together with `flatbuffersJava`/`flatbuffersJavaGrpc` in
  `gradle/libs.versions.toml`, in the same commit.

`flake.nix` has been verified end-to-end: `nix flake check` passes, `nix build .#flatc` produces
a working `flatc version 25.2.10`, and `nix develop -c bash -c 'cd example && ./gradlew test'`
runs all four round-trip tests green with no manual `$FLATC` override. `flake.lock` pins the
exact `nixpkgs` revision that was verified against.

## Using the Gradle plugin

The plugin is published to the [Gradle Plugin Portal](https://plugins.gradle.org/plugin/io.github.microgeant.scarabuff)
as `io.github.microgeant.scarabuff`, so no extra repository configuration is needed:

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm")
    id("io.github.microgeant.scarabuff") version "0.2.0"
}

scarabuff {
    // schemaDir defaults to src/main/fbs, outputDir to build/generated/source/scarabuff
    // flatc defaults to $FLATC (set by `nix develop`), then `flatc` on PATH
    // kotlinPackageSuffix defaults to "" (coroutine wrapper colocated with the flatc Kotlin output)
}
```

`generateScarabuffSources` then runs automatically before `compileKotlin`. You
still need the runtime dependencies yourself (`com.google.flatbuffers:flatbuffers-java`,
`:flatbuffers-java-grpc`, `io.grpc:grpc-stub`, `io.grpc:grpc-kotlin-stub`, a gRPC transport like
`io.grpc:grpc-netty`, `org.jetbrains.kotlinx:kotlinx-coroutines-core`) — see
`example/build.gradle.kts` for a complete, working set at the pinned versions.

To use an unreleased checkout instead, consume it as a composite build (this is what `example/`
does) and drop the `version` from the `plugins {}` block:

```kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("../path/to/scarabuff")
}
```

## Releasing

Publishing is done by [`.github/workflows/publish.yml`](.github/workflows/publish.yml):

1. One-time: create API keys at <https://plugins.gradle.org> and add them as the GitHub repo
   secrets `GRADLE_PUBLISH_KEY` / `GRADLE_PUBLISH_SECRET`.
2. Bump `version` in `gradle.properties`, commit, then tag and push: `git tag v0.2.0 && git push
   origin v0.2.0`. The workflow checks the tag matches the version, runs the build and the
   example's round-trip tests inside `nix develop`, then runs `publishPlugins`.

`scarabuff-core` isn't published separately: it's bundled into the plugin jar via the shadow
plugin, with its own dependencies (KotlinPoet) left as normal POM dependencies. To publish by
hand instead, run `./gradlew :scarabuff-gradle-plugin:publishPlugins` with the keys set in
`~/.gradle/gradle.properties` as `gradle.publish.key` / `gradle.publish.secret`.

## Using the CLI

```
./gradlew :scarabuff-cli:installDist
scarabuff-cli/build/install/scarabuff-cli/bin/scarabuff-cli \
  --schema-dir path/to/fbs \
  --out-dir build/generated \
  --flatc "$FLATC"
```

## Development

```
nix develop            # flatc (pinned), JDK 17, Gradle on PATH, $FLATC exported
./gradlew build         # scarabuff-core, scarabuff-gradle-plugin, scarabuff-cli
cd example && ./gradlew test   # end-to-end round-trip proof, all 4 streaming kinds
```

### Other flake commands

`flake.nix` exposes two outputs per supported system (`x86_64-linux`, `x86_64-darwin`,
`aarch64-darwin` — see the comment in `flake.nix` for why `aarch64-linux` is intentionally
excluded): `packages.<system>.flatc` (aliased as `packages.<system>.default`) and
`devShells.<system>.default`. A few ways to use them beyond `nix develop`:

```
# Run a single command inside the devShell without dropping into it interactively
nix develop -c ./gradlew build
nix develop -c bash -c 'cd example && ./gradlew test'

# Build just the pinned flatc derivation, without JDK/Gradle
nix build .#flatc          # or `nix build` — flatc is the default package
result/bin/flatc --version

# Run the pinned flatc directly, without a `nix build` step first
nix run .#flatc -- --version

# List everything the flake exposes (packages, devShells, etc.)
nix flake show

# Validate the flake evaluates and builds cleanly on the current system
nix flake check

# Update the pinned nixpkgs revision (rewrites flake.lock)
nix flake update
```

Every command above is reproducible off `flake.lock` — no `nix-channel`/`NIX_PATH` involved, and
no dependency on whatever `flatc`/JDK/Gradle happen to already be on your machine.

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).
