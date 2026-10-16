# Changelog

All notable changes to scarabuff. The format follows [Keep a Changelog](https://keepachangelog.com/),
and versions follow the policy in the README's [Versioning](README.md#versioning) section: before
1.0, a minor release (0.x.0) may break things, and always says so here first, with how to migrate.

## [Unreleased]

### Added

- **`<Name>Spec.Vector`, vectors without an object per element,** for structs and tables:
  `NumItemSpec.Vector(n) { i -> put(ids[i], scores[i], flags[i]) }`, or
  `ItemSpec.Vector(n) { i -> put(id = ids[i], name = names[i]) }`. It's a `List` of specs, so it
  goes wherever one does, and table builders write its elements straight into the buffer. A
  struct's `put` takes nested structs' fields flattened; a table's takes the same arguments as
  `build`. Measured with JMH, encoding 1000 elements:
  - structs: 4.8 µs and 48 KB, against 8.5 µs and 84 KB for a list of specs, and 4.6 µs and
    48 KB for hand-written flatc code;
  - tables: 24% less allocation than a list of specs (143 KB against 187 KB), at the same speed
    (52–54 µs).
- **Servers reject requests missing a required field with `INVALID_ARGUMENT`,** naming the field
  by its path through nested tables: `Order is missing required field items[1].sku`. The check
  runs before the implementation sees the request, and for every call shape; streams fail at the
  first bad request. Until now, the implementation threw `AssertionError` when it read the field,
  and the client got `UNKNOWN`. Only request types with required fields, directly or in nested
  tables, are checked.
- **`missingRequiredField()` on every table:** the path of the first missing required field, or
  null. Use it on responses, or on bytes from `fromBytes`. It reads presence without decoding
  anything.

- **FlatBuffers 24.3.25 support,** next to 25.2.10: `scarabuff { flatbuffersVersion.set("24.3.25") }`
  switches both the `flatbuffers-java` the plugin adds and the `flatc` it installs. The new
  `flatbuffersVersion` setting replaces pinning the runtime jar, which conflicted with the
  version the plugin adds. 25.2.10 stays the default: FlatBuffers has released newer `flatc`
  versions, but no `flatbuffers-java` to match them.

- **Builders for unions of tables.** `union Payload { Inner, Other }` gets a `PayloadSpec`
  interface that `InnerSpec` and `OtherSpec` implement, so a union field takes a member's spec
  directly, and the builder writes the type tag. `toSpec()` reads the member back, and
  `missingRequiredField()` checks it. Tables with union fields, and tables nesting them, now get
  builders. Still unsupported: vectors of unions, unions with struct or string members, and
  unions that list a table twice.

### Changed

- **Table builders write vectors of structs and tables through the element's generated `Vector`**
  (`writeElements` and `offsets`), with an index loop for ordinary lists. The wire format is
  unchanged.

### Fixed

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

[0.5.1]: https://github.com/microgeant/scarabuff/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/microgeant/scarabuff/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/microgeant/scarabuff/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/microgeant/scarabuff/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/microgeant/scarabuff/releases/tag/v0.2.0
