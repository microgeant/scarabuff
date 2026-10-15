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

## When to use scarabuff

gRPC's default message format is Protocol Buffers, and Google's
[grpc-kotlin](https://github.com/grpc/grpc-kotlin) only generates Kotlin coroutine stubs for
Protocol Buffers. flatc can generate gRPC code for FlatBuffers in several languages, but not
Kotlin. scarabuff is for the space in between: **Kotlin projects that use gRPC with FlatBuffers as
the message format**.

It's a good fit when FlatBuffers itself is the right choice:

- **Large messages you only partly read.** FlatBuffers messages are read in place, with no parsing
  step, so reading one field costs the same however big the message is.
- **Low allocation.** No parsed object tree means less garbage-collector work, which matters on
  Android and in games, AR or audio, where a GC pause shows up as stutter.
- **Binary payloads** such as audio or image buffers, read directly from the received bytes.
- **A schema shared with native code.** The same `.fbs` works from C++, Rust and Swift.
- **High-rate streams** of small structured messages, like sensor data or poses, over gRPC
  streaming with coroutines.

For an ordinary request/response API without those needs, Protocol Buffers with grpc-kotlin is
usually the simpler choice:

- **Tooling:** `grpcurl`, Postman, server reflection, gRPC-Web and HTTP/JSON transcoding all
  assume Protocol Buffers, and none of them work with FlatBuffers.
- **Ergonomics:** FlatBuffers messages are read-only views over bytes, with nullable accessors and
  [lifetime rules](#working-with-messages); Protocol Buffers gives you ordinary objects.
- **Size:** FlatBuffers messages are often larger on the wire, trading compactness for
  zero-copy reads.

scarabuff's current limits: JVM and Android only, with the schema in a Kotlin/JVM module (see
[Android](#android)). Generated builders don't support structs or unions yet (see
[Building messages](#building-messages)).

## Quick start

**1. Apply the plugin** (published on the
[Gradle Plugin Portal](https://plugins.gradle.org/plugin/io.github.microgeant.scarabuff)):

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.2.10"
    id("io.github.microgeant.scarabuff") version "0.3.0"
}
```

**2. Add a gRPC transport.** The plugin adds everything the generated code compiles against
(flatbuffers-java, flatbuffers-java-grpc, grpc-stub, grpc-kotlin-stub, kotlinx-coroutines-core)
at the versions it's tested with. The transport is up to you:

```kotlin
dependencies {
    implementation("io.grpc:grpc-netty-shaded:1.70.0") // a JVM server or client
    // implementation("io.grpc:grpc-okhttp:1.70.0")    // an Android client; see "Android" below
}
```

You don't need to install `flatc` either: the plugin downloads the one matching your
`flatbuffers-java` version (see [How scarabuff gets `flatc`](#how-scarabuff-gets-flatc)).

**3. Put your schemas in `src/main/fbs/`:**

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

**4. Implement the server and call it from a client:**

```kotlin
import com.example.echo.EchoServiceGrpcKt.EchoServiceCoroutineImplBase
import com.example.echo.EchoServiceGrpcKt.EchoServiceCoroutineStub

// Server: override the RPCs you implement; the rest answer UNIMPLEMENTED.
class EchoServer : EchoServiceCoroutineImplBase() {
    override suspend fun echo(request: EchoRequest): EchoResponse = /* build a response */
    override fun observe(request: EchoRequest): Flow<EchoResponse> = /* ... */
    override suspend fun collect(requests: Flow<EchoRequest>): EchoResponse = /* ... */
    override fun chat(requests: Flow<EchoRequest>): Flow<EchoResponse> = /* ... */
}
// ServerBuilder.forPort(8080).addService(EchoServer()).build().start()

// Client
val stub = EchoServiceCoroutineStub(channel)
val request = EchoRequest.build(message = "hi") // generated; see "Building messages"
val response: EchoResponse = stub.echo(request)
val stream: Flow<EchoResponse> = stub.observe(request)

// Per-call headers (metadata) are an optional last parameter on every stub method
val headers = Metadata().apply { put(Metadata.Key.of("x-trace-id", Metadata.ASCII_STRING_MARSHALLER), "abc123") }
stub.echo(request, headers)
```

Requests and responses are ordinary FlatBuffers tables.
[`EchoRoundTripTest.kt`](example/src/test/kotlin/com/scarabuff/example/EchoRoundTripTest.kt) has
a complete, runnable version of all four shapes.

## Building messages

flatc's Kotlin output has no object API, so on its own every message needs a series of
`FlatBufferBuilder` calls. scarabuff generates a `<Table>Builders.kt` for each table, and a
`<Struct>Builders.kt` for each struct, instead:

```fbs
struct Vec2 { x:float; y:float; }
table User { id:uint; name:string (required); }
table Post { title:string; author:User; tags:[string]; pin:Vec2; route:[Vec2]; }
```

```kotlin
val post: Post = Post.build(
    title = "Hello",
    author = User.build(id = 1u, name = "Ada"), // nested messages are copied in
    tags = listOf("intro", "kotlin"),
    pin = Vec2.build(x = 1f, y = 2f),           // structs too
    route = listOf(Vec2.build(0f, 0f), Vec2.build(3f, 4f)),
)

val bytes: ByteArray = Post.buildBytes(title = "Hello")
val again: Post = Post.fromBytes(bytes)
```

- **Parameters** follow the field order and flatc's names (`created_at` becomes `createdAt`).
  Required fields are non-null parameters with no default, so leaving one out is a compile error.
  Everything else defaults to the schema's default, or `null`.
- **Lists:** vectors take a `List` (`List<Int>`, `List<String>`, `List<User>`, `List<Vec2>`, ...).
- **Structs:** `Vec2.build(...)` returns a `Vec2`, the same type flatc's accessors return, so a
  struct read from one message can be passed straight into another. A struct always has every
  field, so all of `build`'s parameters are required.
- **Lower level:** `Post.pack(builder, ...)` writes into a `FlatBufferBuilder` you own and returns
  the offset, and `post.copyInto(builder)` re-serializes an existing message (for example, one
  you received) into it. Structs have `copyInto` too, which writes them inline; inside a table,
  it must be called right before that field's `add` call.
- **Not supported yet:** tables with union fields or vectors of unions. They still get
  `fromBytes`, but no `build`, and the build says which field is the reason. Build those by hand
  with flatc's `start`/`add`/`end` functions. (Fixed-size arrays in structs never get this far:
  flatc doesn't support them for Kotlin.)
- **Optional scalars** (`count:int = null`) are written correctly even when set to `0`. flatc's
  own `addCount` would silently drop that value.

Turn the whole thing off with `scarabuff { generateBuilders.set(false) }`.

## Working with messages

A few FlatBuffers behaviours that are easy to trip over when messages cross a gRPC call.

**Nullable fields.** Every string, table and vector field is nullable in the generated Kotlin
(`val name: String?`), because FlatBuffers fields are optional by default. Mark the ones that must
always be present `(required)`:

```fbs
table GreetRequest { name:string (required); nickname:string; }
```

`name` is then a non-null `String`, and a required parameter of `GreetRequest.build`. Building a
`GreetRequest` by hand without it throws when the table is finished, and reading a message where a
peer left it out throws too. Both are `AssertionError`s, which `catch (e: Exception)` doesn't
catch.

**Messages are views over bytes.** A table object reads its fields from a `ByteBuffer` on every
access; it doesn't copy them out. With scarabuff's marshallers:

- **Received messages** each get their own freshly allocated buffer, so they're safe to keep,
  pass between coroutines, and read later.
- **Sent messages** are streamed straight from the table's backing array, without a copy. Don't
  reuse or `clear()` a `FlatBufferBuilder`, or write to its buffer, until the call has sent the
  message. The simplest rule is one builder per message.
- **Use heap buffers.** The marshaller needs `ByteBuffer.array()`, which a direct `ByteBuffer`
  doesn't have, so don't give `FlatBufferBuilder` a direct-buffer factory.
- **Map at the edges.** Accessors decode on every call (each `.name` read builds a new `String`),
  and a table keeps its whole message buffer alive. In app code, convert messages into your own
  data classes at the network boundary rather than passing tables around.

## Android

The plugin currently needs the `org.jetbrains.kotlin.jvm` plugin, so it can't be applied to an
Android module directly. Put the schemas in a small Kotlin/JVM module and depend on it from the
app:

```kotlin
// api/build.gradle.kts
plugins {
    kotlin("jvm")
    id("io.github.microgeant.scarabuff") version "0.3.0"
}

// app/build.gradle.kts
dependencies {
    implementation(project(":api"))
    implementation("io.grpc:grpc-okhttp:1.70.0")
}
```

- **Transport:** use `grpc-okhttp` on Android, not `grpc-netty`.
- **JVM target:** keep the schema module's bytecode target at or below the app's. If the app
  compiles for Java 11, set the same in the schema module:

  ```kotlin
  java { targetCompatibility = JavaVersion.VERSION_11 }
  kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
  ```

- **Reaching a server on your machine:**
  - From the emulator, `10.0.2.2` is your machine's `localhost`.
  - Or run `adb reverse tcp:50051 tcp:50051` and connect to `127.0.0.1:50051`. This also works
    for a physical device over USB.

```kotlin
val channel = OkHttpChannelBuilder.forAddress("10.0.2.2", 50051)
    .usePlaintext() // local development only
    .build()
val stub = EchoServiceCoroutineStub(channel)
```

## Compatibility

Each release is tested by building and running `example/` against the published plugin on:

| Gradle | Kotlin |
|---|---|
| 8.7 | 1.9.0 |
| 8.14.6 | 2.1.21 |
| 9.1.0 | 2.2.10 |
| 9.8.1 | 2.4.21 |

Versions in between are very likely fine; these are the ones that are actually checked, by
`./gradlew :scarabuff-gradle-plugin:functionalTest`. Gradle must run on JDK 17 or newer, and the
configuration cache is supported.

## How scarabuff gets `flatc`

`flatc`'s version and the `flatbuffers-java` / `flatbuffers-java-grpc` runtime jars **must
match**. FlatBuffers releases `flatc` and every language runtime together under one version
number, and a newer `flatc` happily emits code the older runtime can't load (for example a
`Constants.FLATBUFFERS_25_12_19()` version check that doesn't exist in the `25.2.10` jar, which
fails at compile time).

So by default the plugin manages `flatc` itself:

1. It reads the `com.google.flatbuffers:flatbuffers-java` version from your compile classpath
   (or `flatcVersion`, if you set it).
2. It downloads that version's official binary from the
   [FlatBuffers GitHub releases](https://github.com/google/flatbuffers/releases), checks it
   against a SHA-256 pinned in the plugin, and caches it in
   `~/.gradle/caches/scarabuff/flatc/`. Later builds reuse it.

Supported versions: **25.2.10**, on macOS (Intel and Apple Silicon), Linux x86_64 and Windows
x86_64. There's no official Linux ARM binary.

To use your own `flatc` instead (another version, Linux ARM, offline builds), set
`scarabuff { flatc.set("/path/to/flatc") }` or the `FLATC` environment variable; `flatc` wins
over `FLATC`. scarabuff then runs that binary as-is, so make sure its version matches your
runtime jars. Changing which `flatc` is used, including swapping the binary at the same path,
regenerates the code.

With Nix, `nix develop github:microgeant/scarabuff` gives a shell with the pinned `flatc` and
`FLATC` set.

## Configuration

All settings are optional:

```kotlin
scarabuff {
    schemaDir.set(layout.projectDirectory.dir("src/main/fbs"))
    outputDir.set(layout.buildDirectory.dir("generated/source/scarabuff"))
    flatcVersion.set("25.2.10")
    kotlinPackageSuffix.set(".grpc")
}
```

| Setting | Default | Description |
|---|---|---|
| `schemaDir` | `src/main/fbs` | Directory containing the `.fbs` schemas. |
| `outputDir` | `build/generated/source/scarabuff` | Where generated Kotlin goes; added to the `main` source set automatically. |
| `flatcVersion` | your `flatbuffers-java` version | Which `flatc` to download and cache. |
| `flatc` | `$FLATC` | Use this `flatc` executable (path, or name on `$PATH`) instead of downloading one. |
| `addRuntimeDependencies` | `true` | Add the libraries the generated code needs to `api`. Set to `false` to declare them all yourself. |
| `generateBuilders` | `true` | Generate `<Table>Builders.kt` for each table; see [Building messages](#building-messages). |
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
  --flatc-version 25.2.10   # or --flatc /path/to/flatc
  # --builders false        # skip <Table>Builders.kt
```

## How it works

### Project layout

- **`scarabuff-core`** — the actual logic, as a plain JVM library with no Gradle API dependency:
  a reader for the services and tables in flatc's binary schema output (`BfbsReader`; flatc
  resolves includes, namespaces and attributes, so scarabuff never parses `.fbs` text itself),
  [KotlinPoet](https://square.github.io/kotlinpoet/)-based generators for the gRPC stubs
  (`GrpcCoroutineGenerator`) and the message builders (`TableBuilderGenerator`), and an
  `Orchestrator` that runs `flatc` and the generators together. Both front ends below call
  the same `Orchestrator.generate(...)`.
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
./gradlew build                # scarabuff-core, scarabuff-gradle-plugin, scarabuff-cli (core tests run flatc)
cd example && ./gradlew test   # end-to-end round-trip proof, all 4 streaming kinds
./gradlew :scarabuff-gradle-plugin:functionalTest   # the Gradle/Kotlin compatibility matrix (slow)
```

Or run a single command without entering the shell: `nix develop -c ./gradlew build`.

### Version pinning

- **JVM dependency versions** (grpc, grpc-kotlin, flatbuffers-java, kotlinx-coroutines, kotlin,
  kotlinpoet) are pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). The plugin
  adds the runtime ones to users' builds at exactly these versions (written into the plugin jar at
  build time), so bumping them there changes what users get.
- **`flatc`** is pinned by [`flake.nix`](flake.nix) to the FlatBuffers `v25.2.10` release
  binaries, for `x86_64-linux`, `x86_64-darwin` and `aarch64-darwin` (there's no official
  `aarch64-linux` binary for that release). `flake.lock` pins the `nixpkgs` revision.
- To bump FlatBuffers: change `flatcVersion` in `flake.nix` (and its per-system asset hashes, see
  the comment there) together with `flatbuffersJava`/`flatbuffersJavaGrpc` in
  `gradle/libs.versions.toml`, in the same commit. To let users' builds download the new
  version, also add its assets and hashes to `FlatcInstaller.RELEASES`.

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).
