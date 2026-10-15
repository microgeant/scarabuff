# Changelog

All notable changes to scarabuff. The format follows [Keep a Changelog](https://keepachangelog.com/),
and versions follow the policy in the README's [Versioning](README.md#versioning) section: before
1.0, a minor release (0.x.0) may break things, and always says so here first, with how to migrate.

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

[0.5.0]: https://github.com/microgeant/scarabuff/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/microgeant/scarabuff/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/microgeant/scarabuff/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/microgeant/scarabuff/releases/tag/v0.2.0
