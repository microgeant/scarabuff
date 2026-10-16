# Changelog

All notable changes to scarabuff. The format follows [Keep a Changelog](https://keepachangelog.com/),
and versions follow the policy in the README's [Versioning](README.md#versioning) section: before
1.0, a minor release (0.x.0) may break things, and always says so here first, with how to migrate.

## [0.6.0] - 2026-10-16

### Breaking

- **`<Struct>.build(...)` is removed,** as 0.5.0 announced. It allocated a builder per struct.
  Use `<Struct>Spec(...)`, which table builders write inline: `pin = Vec2.build(x = 1f, y = 2f)`
  becomes `pin = Vec2Spec(x = 1f, y = 2f)`. The IDE's quick fix on the deprecation does this.
- **The plugin no longer adds `flatbuffers-java-grpc`.** The generated code doesn't use it any
  more (see Fixed). If your own code calls `FlatbuffersUtils`, declare
  `com.google.flatbuffers:flatbuffers-java-grpc:2.0.3` yourself.

### Added

- **Android modules:** the plugin can be applied directly to an Android library or app module,
  alongside the Android Gradle plugin, with AGP 9's built-in Kotlin or the Kotlin Android plugin.
  Each variant gets a `generate<Variant>ScarabuffSources` task, registered through AGP's variant
  API, so the IDE sees the generated sources. The runtime libraries go to `api` in a library and
  `implementation` in an app. Tested with AGP 8.13 (library) and 9.0 (app). A separate Kotlin/JVM
  schema module still works, and remains the way to share a schema with a server.
- **Builders for unions of tables.** `union Payload { Inner, Other }` gets a `PayloadSpec`
  interface that `InnerSpec` and `OtherSpec` implement, so a union field takes a member's spec
  directly, and the builder writes the type tag. `toSpec()` reads the member back, and
  `missingRequiredField()` checks it. Tables with union fields, and tables nesting them, now get
  builders. Still unsupported: vectors of unions, unions with struct or string members, and
  unions that list a table twice.
- **Servers reject requests missing a required field with `INVALID_ARGUMENT`,** naming the field
  by its path through nested tables: `Order is missing required field items[1].sku`. The check
  runs before the implementation sees the request, and for every call shape; streams fail at the
  first bad request. Until now, the implementation threw `AssertionError` when it read the field,
  and the client got `UNKNOWN`. Only request types with required fields, directly or in nested
  tables, are checked.
- **`missingRequiredField()` on every table:** the path of the first missing required field, or
  null. Use it on responses, or on bytes from `fromBytes`. It reads presence without decoding
  anything.
- **`<Name>Spec.Vector`, vectors without an object per element,** for structs and tables:
  `NumItemSpec.Vector(n) { i -> put(ids[i], scores[i], flags[i]) }`, or
  `ItemSpec.Vector(n) { i -> put(id = ids[i], name = names[i]) }`. It's a `List` of specs, so it
  goes wherever one does, and table builders write its elements straight into the buffer. A
  struct's `put` takes nested structs' fields flattened; a table's takes the same arguments as
  `build`. Encoding 1000 elements, measured with `benchmark/`:
  - structs: 4.7 µs and 48 KB, against 8.0 µs and 84 KB for a list of specs, 4.4 µs and 48 KB
    for hand-written flatc code, and 12.1 µs for Protocol Buffers;
  - tables: 143 KB against 187 KB for a list of specs, and 44 µs against 48 µs.
- **FlatBuffers 24.3.25 support,** next to 25.2.10: `scarabuff { flatbuffersVersion.set("24.3.25") }`
  switches both the `flatbuffers-java` the plugin adds and the `flatc` it installs. The new
  `flatbuffersVersion` setting replaces pinning the runtime jar, which conflicted with the
  version the plugin adds. 25.2.10 stays the default: FlatBuffers has released newer `flatc`
  versions, but no `flatbuffers-java` to match them.
- **Benchmarks in the repository:** `benchmark/` runs FlatBuffers against Protocol Buffers with JMH,
  built against the checkout next to it (`cd benchmark && ./gradlew jmh`). It covers encoding,
  decoding, the marshaller, unary RPCs, streaming, and gzip compression. The README's performance
  numbers now come from it, including a new section on when compression pays off. CI compiles it.

### Changed

- **Table builders write vectors of structs and tables through the element's generated `Vector`**
  (`writeElements` and `offsets`), with an index loop for ordinary lists. The wire format is
  unchanged.

### Fixed

- **A Gradle 9 build can include an unreleased checkout** (`pluginManagement { includeBuild(...) }`),
  to try changes before they're published. It used to fail on `HasConvention`, because
  scarabuff's own build used Kotlin 1.9's Gradle plugin and Shadow 8.1. The build now uses Kotlin
  2.2, still targeting Kotlin 1.9 so the plugin keeps running on Gradle 8.7, and
  `com.gradleup.shadow` 8.3. Nothing changes for projects using the published plugin.
- **Sending and receiving messages, in three cases** that `flatbuffers-java-grpc`'s marshaller, used
  until now, got wrong. Each `<Service>GrpcKt` now has its own marshaller, which still sends
  straight from the message's array, with no copy:
  - a message in a direct buffer (built with a direct `ByteBufferFactory`) made the call **hang**;
    it's now copied once and sent;
  - a message in a slice of a larger array (`ByteBuffer.wrap(bytes, offset, length).slice()`) was
    sent from the wrong offset, so the server failed to read it;
  - a transport handing a message over in pieces got it **truncated without an error**; it's now
    read in a loop until complete.
- **Linux ARM and other platforms without an official `flatc`:** the error now gives the exact
  commands to build the matching version from source.

## [0.5.1] - 2026-10-16

### Changed

- **Builders start at an estimate of the message size,** computed from the values passed, instead
  of a flat 1 KB. Measured with JMH against 0.5.0, interleaved on the same machine:
  - small messages: one table item 25% faster with 56% less allocation; one struct 60% faster with
    66% less
  - 1000 items: 5–9% faster for tables and 13% for a struct vector, with about 30% less allocation

  The estimate is cheap: a vector of tables uses its first element times the count. Passing your
  own `builder` still overrides it.
- **Streams allocate about a third less per message,** from the size estimate: 2.9 → 2.0 KB for a
  server-streamed message and 6.1 → 4.3 KB for bidi (Protocol Buffers: 1.8 and 3.9 KB), at the same
  speed.
- **`<field>Equals` is about 30% faster** (JMH, 5 compares per item over 1000 items: 50 → 35 µs,
  still allocation-free). It now compares straight on the message buffer inside the generated
  `ScarabuffFieldAccess_<hash>` object: one presence check and no `ByteBuffer` slice per call.

### Documentation

- **When a builder can be reused across streamed messages:** only when each message is built
  inside the flow gRPC collects, with no buffering, on Netty or OkHttp. It corrupts messages with
  buffered flows and with the in-process transport. `StreamingBuilderReuseTest` in `example/`
  checks the safe case over Netty and OkHttp on every build.

## [0.5.0] - 2026-10-16

### Breaking

- **Nested values in builders are specs.** `build`, `buildBytes` and `pack` take `UserSpec(...)` and
  `Vec2Spec(...)` for nested table and struct fields, instead of finished messages. To migrate,
  replace `author = User.build(...)` with `author = UserSpec(...)`, and pass a message you already
  have as `received.toSpec()`.
- **A table that nests one without builders gets no builders either.** That happens when it holds a
  table with a union field, directly or further down. In 0.4.0 it generated code that didn't
  compile; now the build says which field is the reason.
- **A schema that declares `<Name>Spec` next to `<Name>` fails with an error,** since the generated
  spec class would clash with it.

### Added

- **Specs:** a `<Table>Spec` / `<Struct>Spec` data class for every table and struct, with
  `writeTo(builder)`, `build()` and `buildBytes()`, and `message.toSpec()` to read a received
  message into one.
- **An optional `builder` argument** on `build` and `buildBytes`, cleared first, to presize or reuse
  the `FlatBufferBuilder`.
- **`<field>Equals(value)`** for every string field: compares the UTF-8 bytes without decoding the
  field into a `String`. Its presence check uses a small generated `internal` object in the
  `com.google.flatbuffers` package (`ScarabuffFieldAccess_<hash>`).
- **README sections on [performance](README.md#performance)** against Protocol Buffers, and a
  versioning policy.

### Changed

- **Builders write a whole message tree into one `FlatBufferBuilder`.** There's no longer a buffer
  per nested message, and structs are written inline. Encoding 1000 nested tables is about 3.3×
  faster with 5.6× less allocation; a 1000-item struct vector is about 8.7× faster with 11× less.
- A table's `copyInto(builder)` now goes through `toSpec()`.

### Deprecated

- `<Struct>.build(...)`, which allocates a builder per struct. Use `<Struct>Spec(...)` instead. It
  will be removed in 0.6.0.

### Fixed

- **Generated code could fail to compile** with `Function invocation 'forEach(...)' expected`,
  depending on name lengths. KotlinPoet wrapped long lines just before a lambda's `{`. This
  affected builders since 0.3.0, and was seen with vectors of structs in 0.4.0.

## [0.4.0] - 2026-10-15

### Added

- **Struct support in builders:** `<Struct>.build(...)`, struct fields and vectors of structs in
  table builders, and `copyInto` for structs. Only unions are left unsupported.

## [0.3.0] - 2026-10-15

### Breaking

- **RPC parameters are named `request` / `requests`,** instead of after the request type. Code
  passing them by name (`stub.echo(echoRequest = ...)`) needs updating. Overrides with the old names
  still compile, with a warning.
- **The plugin downloads `flatc` itself,** matching your `flatbuffers-java` version. A `flatc` that's
  only on `PATH` is no longer used; set `FLATC` or `scarabuff { flatc.set(...) }` to use your own.

### Added

- **Per-call headers:** every client stub method takes an optional `headers: Metadata`.
- **Managed `flatc`:** `flatcVersion`, a checksum-verified download cached in the Gradle user home,
  and `--flatc-version` in the CLI.
- **Runtime dependencies:** the plugin adds flatbuffers-java, flatbuffers-java-grpc, grpc-stub,
  grpc-kotlin-stub and kotlinx-coroutines-core (`addRuntimeDependencies`).
- **Message builders:** `build`, `buildBytes`, `fromBytes`, `pack` and `copyInto` for every table
  (`generateBuilders`, `--builders`).
- **A compatibility matrix,** tested in CI: Gradle 8.7–9.8.1 and Kotlin 1.9.0–2.4.21.

### Fixed

- **RPCs were silently dropped** when they used a namespace-qualified type, or an attribute other
  than `streaming` (such as `idempotent`). Schemas are now read from flatc's own binary schema
  output instead of being parsed as text, so includes, namespaces and comments are handled by flatc.
- A request type named `Channel` shadowed the stub's own `channel`, so the stub didn't compile.
- Switching to a different `flatc` didn't regenerate the code.
- Optional scalars set to `0` were dropped. That's a flatc bug the builders work around.

## [0.2.0] - 2026-10-10

### Added

- First release on the Gradle Plugin Portal, as `io.github.microgeant.scarabuff` (approved
  2026-10-14). It generates Kotlin coroutine gRPC stubs for all four call shapes from `.fbs`
  `rpc_service` definitions, as pure Kotlin. A standalone CLI is also included.

[0.6.0]: https://github.com/microgeant/scarabuff/compare/v0.5.1...v0.6.0
[0.5.1]: https://github.com/microgeant/scarabuff/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/microgeant/scarabuff/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/microgeant/scarabuff/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/microgeant/scarabuff/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/microgeant/scarabuff/releases/tag/v0.2.0
