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

## Quick start

**1. Apply the plugin** (published on the
[Gradle Plugin Portal](https://plugins.gradle.org/plugin/io.github.microgeant.scarabuff)):

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "1.9.0"
    id("io.github.microgeant.scarabuff") version "0.2.0"
}
```

**2. Add the runtime dependencies.** scarabuff only generates code; the libraries it compiles
against are up to you:

```kotlin
dependencies {
    implementation("com.google.flatbuffers:flatbuffers-java:25.2.10")
    implementation("com.google.flatbuffers:flatbuffers-java-grpc:2.0.3")
    implementation("io.grpc:grpc-stub:1.70.0")
    implementation("io.grpc:grpc-kotlin-stub:1.2.1")
    implementation("io.grpc:grpc-netty:1.70.0") // or any other gRPC transport
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}
```

These are the versions scarabuff is tested with (see `example/build.gradle.kts`).

**3. Install `flatc` at the same version as `flatbuffers-java`** (25.2.10 above). See
[Getting the right `flatc`](#getting-the-right-flatc) below; this is the one step that's easy to
get wrong.

**4. Put your schemas in `src/main/fbs/`:**

```fbs
namespace com.example.echo;

table EchoRequest { message:string; }
table EchoResponse { message:string; }

rpc_service EchoService {
  Echo(EchoRequest):EchoResponse;                                  // unary
  Observe(EchoRequest):EchoResponse (streaming: "server");
  Collect(EchoRequest):EchoResponse (streaming: "client");
  Chat(EchoRequest):EchoResponse (streaming: "bidi");
}
```

`generateScarabuffSources` runs automatically before `compileKotlin`, so a normal build generates
the table classes and an `EchoServiceGrpcKt` object next to them.

**5. Implement the server and call it from a client:**

```kotlin
import com.example.echo.EchoServiceGrpcKt.EchoServiceCoroutineImplBase
import com.example.echo.EchoServiceGrpcKt.EchoServiceCoroutineStub

// Server: override the RPCs you implement; the rest answer UNIMPLEMENTED.
class EchoServer : EchoServiceCoroutineImplBase() {
    override suspend fun echo(echoRequest: EchoRequest): EchoResponse = /* build a response */
    override fun observe(echoRequest: EchoRequest): Flow<EchoResponse> = /* ... */
    override suspend fun collect(echoRequest: Flow<EchoRequest>): EchoResponse = /* ... */
    override fun chat(echoRequest: Flow<EchoRequest>): Flow<EchoResponse> = /* ... */
}
// ServerBuilder.forPort(8080).addService(EchoServer()).build().start()

// Client
val stub = EchoServiceCoroutineStub(channel)
val response: EchoResponse = stub.echo(request)
val stream: Flow<EchoResponse> = stub.observe(request)
```

Requests and responses are ordinary FlatBuffers tables, built with `FlatBufferBuilder` as usual.
[`EchoRoundTripTest.kt`](example/src/test/kotlin/com/scarabuff/example/EchoRoundTripTest.kt) has
a complete, runnable version of all four shapes, including the builder code.

## Getting the right `flatc`

`flatc`'s version and the `flatbuffers-java` / `flatbuffers-java-grpc` runtime jars **must
match**. FlatBuffers releases `flatc` and every language runtime together under one version
number, and a newer `flatc` happily emits code the older runtime can't load (for example a
`Constants.FLATBUFFERS_25_12_19()` version check that doesn't exist in the `25.2.10` jar, which
fails at compile time).

- **Download the release binary**: grab `flatc` for your platform from the
  [FlatBuffers v25.2.10 release](https://github.com/google/flatbuffers/releases/tag/v25.2.10)
  (`Mac.flatc.binary.zip`, `Linux.flatc.binary.g++-13.zip` or `Windows.flatc.binary.zip`).
- **Or use Nix**: `nix run github:microgeant/scarabuff#flatc -- --version`, or
  `nix develop github:microgeant/scarabuff` for a shell with it on `$PATH` and `$FLATC` set.
- **Avoid `brew install flatbuffers`** and other package managers unless you've checked the
  version: they install the latest `flatc`, which usually won't match your runtime jars.

The plugin looks for `flatc` in this order: the `flatc` setting below, the `$FLATC` environment
variable, then `flatc` on `$PATH`. Run `flatc --version` to check which one you have.

## Configuration

All settings are optional:

```kotlin
scarabuff {
    schemaDir.set(layout.projectDirectory.dir("src/main/fbs"))
    outputDir.set(layout.buildDirectory.dir("generated/source/scarabuff"))
    flatc.set("/opt/flatbuffers-25.2.10/flatc")
    kotlinPackageSuffix.set(".grpc")
}
```

| Setting | Default | Description |
|---|---|---|
| `schemaDir` | `src/main/fbs` | Directory containing the `.fbs` schemas. |
| `outputDir` | `build/generated/source/scarabuff` | Where generated Kotlin goes; added to the `main` source set automatically. |
| `flatc` | `$FLATC`, then `flatc` on `$PATH` | Path to, or name of, the `flatc` executable. |
| `kotlinPackageSuffix` | `""` | Appended to each schema's `namespace` to form the package for the generated `<Service>GrpcKt`. Empty puts it next to the table classes; e.g. `.grpc` puts it in a subpackage. |

The `org.jetbrains.kotlin.jvm` plugin must also be applied; the order doesn't matter.

### Using an unreleased checkout

To try changes that aren't published yet, consume scarabuff as a composite build (this is what
`example/` does) and drop the `version` from the `plugins {}` block:

```kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("../path/to/scarabuff")
}
```

## Using the CLI

For non-Gradle builds, build the CLI from source and point it at the same kind of schema
directory:

```
./gradlew :scarabuff-cli:installDist
scarabuff-cli/build/install/scarabuff-cli/bin/scarabuff-cli \
  --schema-dir path/to/fbs \
  --out-dir build/generated \
  --flatc "$FLATC"
```

## How it works

### Project layout

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

### Why it's pure Kotlin

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

## Development

```
nix develop                    # pinned flatc, JDK 17 and Gradle on PATH, $FLATC exported
./gradlew build                # scarabuff-core, scarabuff-gradle-plugin, scarabuff-cli
cd example && ./gradlew test   # end-to-end round-trip proof, all 4 streaming kinds
```

Or run a single command without entering the shell: `nix develop -c ./gradlew build`.

### Version pinning

- **JVM dependency versions** (grpc, grpc-kotlin, flatbuffers-java, kotlinx-coroutines, kotlin,
  kotlinpoet) are pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).
- **`flatc`** is pinned by [`flake.nix`](flake.nix) to the FlatBuffers `v25.2.10` release
  binaries, for `x86_64-linux`, `x86_64-darwin` and `aarch64-darwin` (there's no official
  `aarch64-linux` binary for that release). `flake.lock` pins the `nixpkgs` revision.
- To bump FlatBuffers: change `flatcVersion` in `flake.nix` (and its per-system asset hashes, see
  the comment there) together with `flatbuffersJava`/`flatbuffersJavaGrpc` in
  `gradle/libs.versions.toml`, in the same commit.

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).
