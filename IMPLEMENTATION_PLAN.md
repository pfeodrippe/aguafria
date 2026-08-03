# Aguafria implementation plan

## Status

The core library and source-conversion pipeline are implemented. The current
acceptance suite covers clean-cache native compilation, implicit returns, FFM
calls, parallel snapshots, namespace-module replacement, external Zig modules,
standalone `ReleaseFast` output, statistics, mapped diagnostics, and the
Zig/ZLS-generated keyword catalog plus the complete EDN-derived Zig std
namespace graph. The complete current 245-file TigerBeetle conversion is
checked in and bulk-loadable as 245 ordinary namespaces with 4,032 top-level
declarations, zero generated `az/defraw` declarations, and zero nested
`raw`/`raw-statements`/type/expression fallbacks. Conversion now fails with
source-located structured data instead of returning generated code if any Zig
AST node lacks a structural Aguafria representation. The complete converted
graph also materializes back into a 619-file TigerBeetle project and passes
`zig build check` with Zig 0.16.0.

The pinned TigerBeetle source now has a verified compatibility-only Zig 0.16
baseline on macOS/aarch64: `zig build check`, the release build and CLI smoke
test, all 349 unit tests, all 44 runnable integration tests, formatting, and the
aggregate `zig build test` pass (3 integration cases are intentionally skipped
by their upstream platform/environment guards). The full native client artifact
matrix (`c`, `dotnet`, `rust`, `go`, `java`, `node`, `python`, and `ruby`), the
C sample, and the auxiliary `vopr:build`, `fuzz:build`, `vortex:build`, and
`scripts:build` targets compile. The representative fuzz-smoke workload passes.
Complete upstream client CI modes now pass locally for Go, Rust, Java, Node,
Python, and Ruby, including live client tests and samples. The .NET 10 SDK was
installed and its unmodified upstream CI started successfully, but its test
phase produced no result within a bounded ten-minute local run and was stopped;
.NET and the complete upstream multi-OS matrix remain external coverage. These
changes preserve TigerBeetle behavior and contain no Aguafria instrumentation.

Standalone behavioral parity is the current release gate. Exact preservation
of Zig comments, whitespace, source-byte quines, embedded-source checksums, and
project-specific style policy is not part of that gate; generated Zig may use
different local/import names and layout when its compiled behavior is the same.
The converted tree currently passes every runnable behavioral test reached by
the aggregate; its remaining two failures and one crash are the AMQP
source-byte checksum, `tidy`, and the source quine. The full integration suite,
cross-platform C-client/auxiliary builds, paired deterministic VOPR seeds
1/42/123/999, and fuzz smoke now pass. Each paired VOPR run produces identical
output, and the original/converted fuzz-smoke runs completed in 23.548s and
23.675s respectively. Broader performance and language-client evidence remains
explicit follow-up work; local standalone behavioral parity is established.

The first true live native Var layer is now implemented for named,
non-generic functions in one namespace and across transitive, including
cyclic, namespace graphs. Dispatch now covers Zig-only/private functions,
named struct-by-value signatures, and inferred error unions as well as scalar
C exports; only scalar C ABI functions are directly invokable from the JVM.
Development libraries contain stable, ABI-keyed dispatch cells; an
ABI-compatible callee edit repoints already-compiled callers without
recompiling them, while final `ReleaseFast` builds retain direct static calls
and contain no dispatch machinery. Development compilation gives every
Clojure namespace one logical Zig module in an immutable dependency snapshot
and uses a tiny loader root, so the root may participate in a cycle without
being compiled twice. Zig comptime calls take the current static implementation
while runtime calls use the dispatch cell. Native and JVM-side active-call
accounting keeps an obsolete library loaded across an in-flight call and
retires its arena after quiescence. Breaking scalar ABI versions coexist and
old callers remain on the old version until reevaluated.

This generic path now passes both a hand-written two-namespace cyclic hot-
reload test and real converted TigerBeetle probes: already-compiled callers
observe `compaction_op_min` change from 96 to 97 and back to 96 and
`stdx.zeroed` change from true to false in the same JVM without changing the
callers' implementation generations. Initial development generations use
erased zero-valued dispatch addresses that fall back to their local Zig
implementation. An implementation-address getter is emitted for C exports
(which Zig must already analyze) and, otherwise, only when that specific Var
is changed, preserving Zig's normal lazy/platform analysis.
The complete converted `tigerbeetle.main` namespace now publishes in a fresh
JVM with 38 declarations, eight callable versions, and zero failed builds.
Atomic
multi-module SCC publication now prepares the complete component in parallel
and publishes it behind one native odd/even epoch. Stable schema-keyed native
state capsules survive compatible reloads, explicit migrations publish
breaking state versions, and versioned struct/container schemas let old and
new callers coexist. Build-step-dependent path options are now captured by an
options-only Zig build and stored as relocatable bundles.
The library now also has a separate `aguafria.zig.host` development API that
constructs Zig 0.16 `std.process.Init`, runs a native main on a dedicated JVM
thread, exposes host lifecycle through serializable statistics, and transfers
native state-capsule ownership to/from the running program. The actual
converted TigerBeetle main runs its `version` command in that hot-reload PID
and exits successfully; a blocking hand-written main follows a compatible
callee edit while it remains active. The real converted VOPR also completes
the deterministic 100-request seed-1 run with the original 22,666 ticks. In a
long-running performance VOPR, generation 41 → 45 published while the host was
still `:running`; the edit added a new Var and function and rewired existing
`full_core` to call it, after which the same process exited 0 and the new Var
read 4,242. Cross-module comptime/type-factory propagation is automatic for
schema-compatible edits: type references carry separate layout and
comptime-implementation identities, and dependent SCCs compile from one staged
graph before atomic publication. Breaking layouts instead publish an
independent root generation; untouched callers/state remain on the old schema
until those Vars are explicitly reevaluated and, for state, migrated. This is
the requested Clojure model—old B keeps calling A@v1 after A breaks. Clojure
Var metadata is refreshed from every successful native preparation so later
REPL forms resolve the current schema/ABI without hidden namespace metadata.
The actual converted TigerBeetle `OptionsType` structural publication now also
passes in a running VOPR host. A separate real VOPR acceptance changes the
signature of existing `full_core`, requests an existing `log_performance_mode`
state change from `bool` to a struct while the old host remains live, then at
natural quiescence explicitly migrates the state and adopts the new caller
graph. Both deterministic 2,000-request hosts report `PASSED (10772 ticks)` in
the same JVM PID. Arbitrary live stack/heap object graphs are intentionally not
reinterpreted; they require an application-defined safe point and migration.
The current complete Kaocha regression run passes 87 tests and 2,986
assertions with zero failures.

## Goal

Build a `deps.edn` Clojure library that treats Clojure data as a thin,
deterministic representation of Zig syntax.  `aguafria.zig/defn` should emit
ordinary Zig, compile it with the installed Zig toolchain, and expose scalar
`export fn` declarations as normal Clojure functions.

The first release deliberately does not depend on a Zig runtime. Forms such as
`while`, Zig operators, builtins, field access, indexing, and struct/array
literals map directly to Zig source. The one intentional Clojure convenience
is an implicit return for the final expression of a non-void function.

## Design

1. **Project and public API**
   - Add `deps.edn`, source/test paths, Kaocha discovery, and an nREPL alias.
   - Expose `aguafria.zig` as the user namespace (normally aliased to `az`).
   - Expose Zig-only keywords/operators and reader-hostile Zig syntax through
     generated, documented Vars in `aguafria.keyword` (normally aliased to
     `ak`) while keeping Clojure-native language forms such as `if` and
     `while` bare. Expose structural Aguafria helpers such as `az/field` and
     `az/while-loop` as real documented Vars as well.
   - Expose Zig's entire public std graph as normal require-able namespaces
     under `aguafria.std`, including nested container members and docs, with a
     single runtime bootstrap reading the generated EDN catalog and no
     generated Clojure namespace source.
   - Provide `az/defn`, `az/defconst`, `az/defvar`, `az/defstruct`, and the
     remaining structural declaration macros. Accept an optional docstring and
     ordinary attr-map after the name; allow `defconst`/`defvar` to omit `_`
     when the Zig type is inferred.
   - Model struct fields as Malli-style `[field type]` or
     `[field properties type]` entries and retain per-field properties in Var
     metadata for REPL inspection and future Zig field features.

2. **Zig emitter**
   - Emit types, identifiers, literals, expressions, statements, and top-level
     declarations without evaluating the Zig forms as Clojure.
   - Cover scalar types; pointer/slice/array/optional/error-union types;
     SIMD vector and C pointer types;
     arithmetic, comparison, boolean, bitwise, wrapping, saturating, and
     assignment operators; calls and builtins; field/index/slice access;
     `if`, `while`, `for`, `return`, `break`, `continue`, locals, blocks,
     `defer`, `errdefer`, `try`, `catch`, and raw Zig escape hatches.
   - Recursively insert implicit returns for final expressions, tail `if`
     branches, and tail `do` blocks while retaining explicit early returns.
   - Keep output deterministic and reject malformed forms with useful
     `ex-info` data.
   - Represent imported modules and members as normal Clojure namespace aliases
     and real, inspectable Clojure Vars;
     reject unresolved dotted/qualified references instead of treating them as
     magical Zig identifiers. Converted modules use ordinary eager `:as`
     requires for acyclic graph edges and reserve `:as-alias` only for edges
     that close a real Zig import cycle, as recorded in a generated EDN
     catalog; generated namespaces contain no Aguafria metadata and no Zig file
     paths. Clojure namespace identity is the live compiler-module identity.
     The compact project EDN catalog may retain project-relative output paths
     for materialization and asset/layout reconstruction, but never machine-
     absolute source paths or a runtime dependency on the original Zig tree.
     Do not generate empty `az/defimport` declarations for converted files.
   - Qualify generated `ak/...` aliases to their `aguafria.keyword/...` Var
     symbols at macro-expansion time, independent of the chosen alias. Have the
     emitter consume those Vars directly—without an intermediate `builtin`
     form—and support type-producing calls such as `ak/Vector` as types.

3. **Compilation and loading**
   - Keep a per-Clojure-namespace declaration registry.
   - On every declaration, regenerate the namespace's complete Zig module,
     content-address it, and compile it as a shared library with `zig
     build-lib`.
   - For converted namespaces, register each declaration's inspectable source
     immediately and use the EDN catalog's exact declaration count as the
     initial compilation boundary; evaluating a complete generated file must
     never compile a half-loaded module or record those invalid snapshots as
     failures. Debounce later single-Var reevaluations. Load missing converted
     dependency sources cycle-safely from the EDN graph before compiling,
     without user batching calls or namespace metadata.
   - Cache generated source/binaries under `.aguafria/zig` and reload the
     entire namespace module so dependent Zig functions see redefinitions.
   - Surface compiler failures with the generated source path, command,
     captured diagnostics, and declaration source metadata.
   - Offer opt-in asynchronous compilation through library configuration or
     `AGUAFRIA_ASYNC_COMPILE`; compile immutable snapshots concurrently and
     publish only the newest requested namespace generation.
   - Validate cached artifacts and publish output atomically so an interrupted
     async compiler cannot poison later REPL sessions.
   - Accept CPU/target/raw Zig options and named Zig module roots, and build
     standalone executables, libraries, or objects from the same source.

4. **Clojure invocation**
   - Use the JDK Foreign Function & Memory API, avoiding a runtime dependency
     beyond Clojure itself.
   - Bind exported Zig functions with scalar C ABI signatures and coerce
     arguments/results at the boundary.
   - Keep the Clojure wrapper stable while each invocation resolves the latest
     loaded namespace generation, enabling REPL-oriented hot reload.
   - Allow arbitrary Zig types for code generation while clearly rejecting
     direct Clojure invocation for ABI types not yet supported.
   - [x] Replace internal descriptor-map Var roots with real Clojure values:
     exact JVM scalars where lossless, and inspectable FFM-backed Zig values
     otherwise. `defconst`, live `defvar`, and type Var roots are semantic;
     emission references remain exclusively in Var metadata.
   - [ ] Complete development-only native-value bridges. Constant getters and
     pointer-based function bridges now cover odd-width integers, arbitrary
     `u64`, normal/extern structs, nested packed structs/bitfields, arrays, and
     SIMD vectors with explicit Clojure constructors, plus exact keyword
     construction for enums (including explicit/nonsequential tags) and
     single-entry maps for tagged unions. Untagged union constructors retain
     the caller's explicit active-field interpretation; arbitrary returned
     untagged values correctly remain ambiguous. Live `defvar` values support
     checked, in-place `az/set-value!` without a build. Add optionals, error
     unions, pointers, and owned/borrowed slices.
   - [ ] Finish native-value lifetime coverage. Native generations are pinned
     while FFM-backed values reference their bytes; type/size/alignment/segment,
     idempotent explicit close, automatic Cleaner release, and use-after-close
     diagnostics exist. A compatible publication test proves old native values
     pin their dylib, cross into new code, and release it on close. Add
     breaking-schema stale-value and GC-triggered retirement acceptance tests.

5. **Verification and documentation**
   - Generate the complete `@builtin` list and compiler flags from Zig's
     `std/zig/BuiltinFn.zig`, ordinary keywords from `std/zig/tokenizer.zig`,
     and enrich signatures/docs from a version-matched ZLS language reference.
   - Generate a std EDN catalog from Zig's authoritative `-femit-docs`
     semantic graph. At runtime, materialize its aliases, nested containers,
     signatures, docs, and source locations as genuine Vars without generated
     namespace shims or handwritten wrappers.
   - Check in the deterministic catalog for normal library use; provide
     `:generate-keyword` and `:check-keyword` aliases so Zig upgrades are a
     mechanical regeneration with source-hash drift detection.
   - Unit-test type/expression/statement emission and invalid input.
   - Integration-test source generation, native compilation, invocation,
     cross-function calls, declarations, and hot reload against the installed
     Zig compiler.
   - Add a README with the exact DSL-to-Zig mapping, setup requirements,
     examples, generated-artifact behavior, and the intentional v0.1 limits.
   - Render Rust-style emitter/compiler failures mapped to the original
     Clojure form and generated Zig while preserving structured diagnostics.
   - Treat editor-provided source metadata as untrusted: multiline CIDER buffer
     contents must never enter generated Zig through source-map comments.
   - Expose serializable aggregate/per-module compilation statistics for REPL
     inspection and a future live monitor.
   - Treat performance as an acceptance property. Benchmark and profile AST
     conversion, EDN-backed namespace/Var interning, declaration emission,
     dependency analysis, clean and cached compilation, parallel scheduling,
     reload publication, native invocation, and reload-dispatch indirection.
     Optimize measured bottlenecks with incremental/content-addressed caches,
     bounded parallelism, persistent indexes, batched I/O, and reduced
     allocation where appropriate, without weakening determinism or errors.

6. **Zig-to-Aguafria source conversion**
   - Parse source with the selected Zig compiler's own `std.zig.Ast`; never
     infer declaration boundaries with regular expressions. Return structured,
     source-located parse diagnostics when the compiler rejects a file.
   - Provide `parse-file`, `zig->clojure`, `convert-file!`, and `convert-tree!`
     in the opt-in `aguafria.zig.convert` tooling namespace. Keep these
     infrequent operations out of the everyday `aguafria.zig` (`az`) DSL
     surface. Namespace naming, source/output roots, overwrite policy,
     formatting, and verification must be explicit options, and every
     operation must return a serializable conversion report.
   - Translate imports, functions, constants, variables, structs, ordinary
     operators, language forms, and `@` builtins to normal `az/...`,
     Clojure-native forms, `aguafria.std...` Vars, and `ak/...` Vars. Reject
     generated Zig/Aguafria syntax list heads that do not resolve through a
     real Var or Clojure itself. Preserve comments, logical blank-line groups,
     and source locations for documentation and error mapping. Render ordinary Zig
     comments as `;;` at their corresponding Clojure declaration/member/body
     positions, and render Zig doc comments as idiomatic declaration
     docstrings.
   - Never emit `az/defraw`, `(raw ...)`, `(raw-statements ...)`, or chunked raw
     equivalents in converted user code. Every declaration, nested container,
     expression, type, and statement must have an inspectable structural
     Aguafria representation. During implementation, count and source-locate
     unsupported nodes and fail conversion instead of silently preserving them
     as opaque Zig.
   - Preserve declaration order and emit only ordinary namespace/declaration
     forms—no batching markers in generated user code. Put optional bulk
     source-only loading in `aguafria.zig.convert`, where an external dynamic
     collector can register a large file once without changing where any Var
     is interned. Normal require/evaluation remains ordinary Clojure.
   - Verify semantic source preservation by rendering the generated Aguafria
     declarations back to Zig, formatting both sides with Zig 0.16.0, and
     comparing the normalized source. Expose mismatches as structured reports.
   - Convert every `.zig` file under `sample/` into checked-in generated
     Clojure namespaces, require them, run the equivalent Zig tests/build, and
     demonstrate that reevaluating a translated declaration hot reloads it.
   - Pin TigerBeetle as `vendor/tigerbeetle`, convert its complete Zig corpus,
     and publish aggregate structural/fallback/round-trip statistics. Keep the
     upstream source behavior unchanged. Zig 0.14.1-to-0.16.0 compatibility
     changes live directly in the submodule and must remain an explicit,
     reproducible compatibility-only patch; they must not contain Aguafria or
     hot-reload instrumentation.

7. **Clojure-like live native Vars and versioned ABI**
   - Keep this engine project-generic. TigerBeetle is a stress/acceptance case,
     not a special runtime: games, web servers, simulations, CLI tools,
     databases, and libraries use the same conversion and reload machinery.
     Long-lived sockets, GPU/window handles, allocators, and similar resources
     live in stable host/state capsules rather than project-specific reload
     code.
   - Give every callable declaration a stable logical Var identity and an ABI
     fingerprint derived from its calling convention, parameter types, return
     type, and relevant attributes.
   - Compile calls between reloadable Aguafria functions through a stable
     dispatch cell for the referenced ABI version. Native code inside a
     function remains ordinary optimized Zig; only a reloadable Var call
     crosses this pointer indirection.
   - For a body-only compatible redefinition of `A`, atomically swap `A`'s
     dispatch cell. Existing compiled callers such as `B` must observe the new
     `A` without recompiling `B`.
   - For a breaking signature change, publish `A@v2` with a new cell while
     retaining `A@v1`. Existing `B@v1` must remain bound to and functional with
     `A@v1`; a corrected/re-evaluated `B@v2` may bind to `A@v2`.
   - Build and maintain a project dependency graph across Clojure namespaces.
     Package strongly connected declarations together when Zig requires it,
     while preserving per-Var logical identity and REPL behavior.
   - Publish a generation only after its complete dependency component
     compiles and loads. Swap all cells for a component atomically so no caller
     observes a half-published graph.
   - Track active calls/generations. Retire old libraries and ABI cells only
     after quiescence proves that no in-flight call or retained dependency can
     enter them.
   - Version `defstruct` layouts using a schema/ABI fingerprint. Keep old and
     new layouts side by side; never reinterpret old memory as a new layout.
   - Host live `defvar` state in stable, version-tagged state capsules. A
     compatible code reload preserves the capsule. A layout change preserves
     the old capsule and requires an explicit user migration function before
     publishing migrated state; Aguafria must not guess field semantics.
   - Make the same rules apply within one namespace, across namespaces, and
     across dependency cycles. Clojure namespace placement affects naming and
     build organization, not hot-reload capability.
   - Expose all logical Vars, ABI/schema versions, dependency edges, current and
     retired generations, active call counts, pending swaps, migration state,
     and errors through the serializable statistics/inspection API.
   - Keep development and production semantics source-compatible. Development
     builds enable dispatch/versioning; `az/build! {:reloadable? false
     :optimize "ReleaseFast"}` resolves calls statically and emits a normal
     standalone Zig artifact with no JVM or Aguafria runtime.

## Execution checklist

### TigerBeetle Zig 0.16 baseline

- [x] Pin the upstream TigerBeetle repository as a submodule.
- [x] Finish the explicit Zig 0.16.0 compatibility changes in that submodule
  for the local macOS/aarch64 baseline: `zig build check`, release build and
  CLI smoke test, 349/349 unit tests, 44/44 runnable integration tests with 3
  upstream-guarded skips, formatting, and aggregate `zig build test` all pass.
- [x] Compile the auxiliary local executable matrix with Zig 0.16.0:
  `vopr:build`, `fuzz:build`, `vortex:build`, and `scripts:build`.
- [x] Build the complete `clients:c` cross-platform artifact matrix and C
  sample, including the Linux and Windows targets, on Zig 0.16.0.
- [x] Exercise the deterministic representative fuzz-smoke workload; the
  handwritten baseline completes successfully in 23.548s.
- [x] Build the full native language-client artifact matrix on Zig 0.16.0 and
  run every locally supported upstream client CI mode. Go, Rust, Java, Node,
  Python, and Ruby pass their complete CI paths, including live tests/samples;
  `test:jni` also passes. Go covers format/vet, unit tests, and four live
  samples; Python covers 29 live pytest cases and four samples.
  The aggregate release prerequisite now avoids a debug/release shared-output
  race, embedded C clients initialize a valid Zig 0.16 `std.Io`, and valid
  non-exhaustive protocol-operation values are safe to format in diagnostics.
- [x] Run the Go runtime client suite with its host SDK; the complete upstream
  `zig build ci -- go` mode passes on macOS/aarch64.
- [ ] Finish the .NET runtime client suite, then run the complete upstream
  language-client matrix on its Linux, Windows, macOS, x86_64, and aarch64 CI
  hosts. The .NET 10 SDK installs and the unmodified suite reaches its live
  integration tests, but the bounded local attempt produced no final result;
  every native Zig artifact build already passes.
- [x] Refresh `vendor/tigerbeetle-zig-0.16.patch` from the complete current
  tracked submodule diff: 1,666 lines across 21 compatibility-only files. It
  applies cleanly to a fresh local clone of pinned commit `40868ce…`, passes
  `git diff --check`, and produces byte-identical files, without staging or
  changing either index.
- [x] Record original/upstream behavioral outputs used for equivalence: CLI
  `version`/`--help`, full unit/integration exits, VOPR seeds 1/42/123/999, and
  deterministic fuzz-smoke output/workload completion.

### Conversion correctness

- [x] Parse all 245 TigerBeetle Zig files with Zig's AST and generate one
  ordinary Aguafria Clojure namespace per Zig file.
- [x] Bulk-load all generated namespaces with Vars interned in their declared
  namespaces and zero top-level `az/defraw` declarations.
- [x] Replace converted relative `az/defimport ... []` declarations with
  normal `:as` requires on acyclic edges, cycle-safe `:as-alias` only on
  cyclic edges, and real target Vars. Resolve the
  few unavoidable reader/structural name collisions through an EDN catalog,
  keep generated namespaces free of Aguafria metadata and Zig paths, and use
  Clojure namespace identity for live compiler modules.
- [x] Make an arbitrary generated namespace directly evaluable in a fresh
  REPL: discover its EDN catalog from the source/classpath, register the full
  reachable cyclic dependency source graph automatically, coalesce whole-file
  declaration loads, and publish the final requested module generation. The
  `message-buffer` acceptance case loads 138 reachable modules and compiles
  without manual preload or `batch/begin!`.
- [x] Verify generated-code hot publication in a fresh JVM by adding and then
  reevaluating one `az/defn`: its stable Clojure Var returns 10 before the edit,
  11 afterward, and advances from native generation 5 to 6.
- [x] Resolve converted source modules (for example `stdx`/`vsr`) as ordinary
  required namespaces. Preserve compiler/build-provided module values such as
  `builtin`, `root`, and build option modules as ordinary `az/defconst` Vars
  initialized with `ak/import`; generated project code contains no empty
  `az/defimport` declarations or invented member stubs.
- [x] Ask Zig's own version-matched build configure graph for value-based
  `Step.Options` modules and store their generated Zig source in the project
  EDN catalog, associated with the importing module and selected build profile.
  `convert-tree!` captures the default step automatically and accepts
  `:build-steps` for another profile. Runtime compilation materializes these
  named modules automatically, while an explicit `az/configure! :modules`
  entry remains an intentional override. A fresh-JVM TigerBeetle namespace
  test compiles with an empty manual module map and proves its command uses the
  captured `vsr_options`; the checked conversion now captures both the default
  and `vopr` profiles mechanically, with six module attachments across four
  owners and root-specific precedence for `vsr_options`/`vsr_vopr_options`.
- [x] Recreate `Step.Options.addOptionPath` and other build-step-dependent path
  values without retaining the original checkout/cache. A version-matched
  synthetic Zig build target runs only selected option nodes and their path
  producers, captures ordinary generated Zig after `addOptionPath` values are
  appended, bundles referenced files/directories, and records relocatable EDN
  tokens. Runtime resource loading resolves filesystem or packaged-resource
  bundles automatically. Tests cover a project source path and a generated
  `Compile.getEmittedBin()` executable; TigerBeetle's real
  `test:integration:build` profile resolves three path values across
  `test_options` and `vortex_options` with no conflicts or unresolved paths.
- [x] Support declaration docstrings and ordinary attr-maps after names for
  `defn`, `defconst`, `defvar`, and `defstruct`; let inferred-type
  `defconst`/`defvar` omit the `_` placeholder, and generate this clean syntax.
- [x] Normalize `defextern` and `deffield` to accept docstrings/ordinary
  attr-maps after the name; `defcomptime` follows the same convention and
  nameless `deftest` accepts a leading attr-map.
- [x] Remove generated layout bookkeeping (`:zig/order`, `:zig/leading`, and
  `:zig/trailing`) from user-facing Clojure. Preserve declaration order inside
  the emitter/runtime, compact positive declaration booleans into
  `:attrs #{...}`, and show retained comments as actual `;;` source comments
  instead of metadata-map noise.
- [x] Omit an empty `:attrs` set and its entire argument from generated source;
  preserve top-level no-flag semantics through the EDN project catalog and use
  natural no-flag defaults for nested members such as
  `(az/field-decl uniform_bit :u1)`.
- [x] Preserve original logical paragraph breaks between function statements,
  with blank lines containing no indentation-only whitespace.
- [x] Represent converted Zig object/struct literals with ordered
  `(az/object [[:field value] ...])` entry vectors instead of Clojure maps.
  Named struct construction remains ergonomic as `(Foo {:field value})`, but
  anonymous type inference, comptime reflection, serialization, and exact
  regeneration never depend on hash-map iteration order.
- [x] Emit Zig local-variable declarations through the real qualified
  `ak/var` Var. Do not leave bare `(var ...)`, whose Clojure meaning is a
  fundamentally different special form.
- [x] Render multiline Zig documentation as readable multiline Clojure string
  literals/docstrings with real line breaks, never a single escaped `\n` line.
- [x] Back every non-Clojure syntax head with a documented `ak/...` or `az/...`
  Var, generate keyword/operator entries from Zig 0.16's tokenizer, and fail
  conversion if known syntax remains unresolved.
- [x] Regenerate the complete TigerBeetle corpus with compact attrs/comments
  and assert that no obsolete ordering/layout/false-boolean metadata remains;
  the generated corpus contains 10,390 positioned `;;` comment lines.
- [x] Eliminate all nested raw expression/statement/type fallbacks from all 245
  generated namespaces—not only the selected executable path.
- [x] Add a hard conversion/generation test that rejects every raw boundary in
  converted code and reports the unsupported Zig AST node with source context.
- [x] Materialize the full converted TigerBeetle graph and pass
  `zig build check` with Zig 0.16.0 rather than merely loading structural
  declarations. The Kaocha regression performs a fresh 245-file conversion,
  materializes all 619 project files, and runs this compiler gate.
- [x] Rebuild handwritten and materialized TigerBeetle executables from the
  same source commit and compare representative CLI behavior: `version` and
  the complete 4,478-byte `--help` output are byte-identical.
- [x] Produce a self-contained converted-project bundle containing the
  generated Clojure/EDN source of truth plus every non-Zig project asset. It
  must materialize and build without consulting any original `.zig` file or
  the original TigerBeetle checkout. The checked bundle contains 245 generated
  namespaces and 374 non-Zig assets; its test substitutes a nonexistent
  original input root before materializing and compiling all 619 files.
- [x] Allow `materialize-project!` to create a nonexistent destination root;
  cover that public API path with a focused Kaocha regression rather than
  requiring callers to create an empty directory first.
- [x] Run every materialized Zig module through the configured `zig fmt` by
  default and surface formatter failures as Aguafria diagnostics. Preserve
  clean original declaration/import placement in the EDN catalog rather than
  reintroducing `:zig/order` noise into generated Clojure.
- [ ] Deferred/non-gating: complete lossless source-trivia/layout regeneration
  for programs that intentionally observe their own source. TigerBeetle's
  quine, embedded-source checksum, and stricter `tidy` policy do not measure
  compiled behavior and are not standalone parity blockers.
- [x] Run the regenerated, self-contained TigerBeetle tree through the complete
  behavioral unit/integration, deterministic VOPR/simulation, fuzz
  build/workload, auxiliary executable, and aggregate matrix. The full
  integration suite passes; all behavioral unit cases pass; VOPR seeds
  1/42/123/999 have byte-identical output; fuzz smoke passes in 23.675s; and
  `clients:c`, its sample, VOPR, fuzz, Vortex, and scripts all build. The three
  source-observer/style exceptions remain separately recorded, and separate
  language-client/full-CI gaps remain explicit.
- [x] Compare executable behavior, protocol-visible deterministic simulation
  results, exit status, and selected upstream tests with the handwritten Zig
  baseline. `version` and the complete `--help` output match, and all four VOPR
  output hashes match. Exact normalized source is intentionally not a behavior
  requirement.

### Live reload engine

- [x] Add stable logical Var identities plus deterministic callable ABI and
  struct/container schema fingerprints. Store them in Var metadata and the
  serializable statistics API; body/doc changes preserve their compatible key
  while signature/field/order/layout changes produce a breaking key.
- [x] Generate stable ABI-keyed dispatch cells for exported scalar functions
  and route same-namespace reloadable inter-Var calls through them. A compatible
  callee body edit repoints already-compiled callers without changing their
  implementation generation.
- [x] Apply development dispatch to addressable Zig-only scalar helpers as well
  as C-exported functions. A non-exported `pub fn` now hot-swaps existing Zig
  callers within one namespace and across a required namespace, while only
  actual C exports remain directly callable from Clojure through FFM. This is
  required for converted projects such as TigerBeetle, whose ordinary logic is
  predominantly Zig `pub fn`, not `export fn`.
- [x] Add native and JVM-side active-call accounting plus safe quiescent
  retirement for scalar shared-library generations. A concurrent integration
  test publishes a replacement while the old native call remains active,
  observes its retirement-pending state through `az/stats`, and proves that its
  arena is unloaded only after the call returns.
- [x] Extend atomic publication from a single namespace to complete dependency
  components. Every SCC member compiles and loads in parallel before one
  registry transition; native dispatch wrappers share an odd/even publication
  epoch so they cannot enter a half-updated cell set, and preparation or setter
  failure restores the complete prior component before releasing callers.
- [x] Route cross-namespace scalar calls through imported development dispatch
  cells. Adding `A/new-a`, hot-rewiring new and existing `B` Vars,
  compatible `A`-only swaps without recompiling `B`, and breaking `A@v1`/`A@v2`
  coexistence all pass in one PID with async compilation. Default exported
  functions are `pub export fn`, making them both C-callable and Zig namespace
  members.
- [x] Capture the exact development source, direct dependency table, and
  dispatch entries of the complete registered transitive graph when an async
  compilation job is queued, so a concurrent callee edit cannot make the
  caller compile one generation and bind symbols from another. Converted
  dependencies that are unresolved during first-file registration are loaded
  cycle-safely before the immutable snapshot is refreshed.
- [x] Compile development graphs as logical Clojure-namespace Zig modules
  behind a tiny loader root. The compilation root may therefore appear in an
  A↔B cycle exactly once, without a duplicate module or dependency dispatch
  entry overwriting the root-owned cell. A hand-written cyclic A/B integration
  test proves an existing caller follows an A-only compatible swap without
  recompilation.
- [x] Preserve Zig `@import("root")` behavior behind the synthetic development
  loader. Forward the selected application's public root declarations and add
  its profile module to the root dependency table. A focused regression proves
  a dependency observes its application's root config, and converted VOPR now
  selects `test_min` (`clients=2`) exactly like the original instead of silently
  falling back to the production config (`clients=16`).
- [x] Preserve Zig comptime semantics for reloadable scalar helpers: calls made
  during comptime use the statically compiled implementation and do not execute
  atomic active-call accounting, while runtime calls still use the swappable
  cell. Permit absent transitive dispatch exports only when an unreachable
  platform-specific Zig declaration was lazily omitted; partial getter/setter
  export pairs remain an error.
- [x] Make dispatch publication demand-driven and type-erased at rest. Every
  eligible wrapper owns an atomic `usize` cell where zero means its local
  implementation; setters never reference `@TypeOf(&implementation)`, and an
  implementation getter is emitted for a C export (which is necessarily
  analyzed for JVM invocation) or for an already-published Var whose
  implementation changed. Getter demand also detects a live dependency
  generation that embedded an older source-only implementation before the
  owner published for the first time; a 15-run fresh-JVM stress acceptance
  covers that async race. This keeps unreachable Windows/Linux/debug code lazy
  on another host while struct-by-value and inferred-error-union hot-swaps
  still update already-compiled callers.
- [x] Gate the first compilation of every converted namespace on the exact
  declaration count stored in `aguafria-project.edn`. A fresh require of the
  full converted TigerBeetle main graph publishes successfully without any
  half-file compiler failures appearing in `az/stats`.
- [x] Expose deterministic direct dependencies, reverse dependents, SCC ids,
  member lists, and cycle flags through `az/stats`. The loaded 245-module
  TigerBeetle graph currently resolves to 123 components, 6 cyclic components,
  and a largest cyclic component of 97 modules; this is the measured atomicity
  problem the component publisher must handle efficiently.
- [x] Build the complete dependency graph and publish strongly connected/cyclic
  components atomically from one immutable dependency snapshot. The explicit
  `az/recompile-component!` recovery path records component ids, members,
  generations, publication timestamps, and rollback errors in `az/stats`.
- [x] Add stable versioned `defvar` state capsules and an explicit migration
  API. Development emission routes same- and cross-namespace state references
  through schema-keyed native pointer cells backed by the first published
  library generation; compatible reloads reuse that address, breaking schemas
  retain the old capsule, and `az/migrate-state!` accepts only an exported
  `(usize old, usize new) void` Zig migration before atomic SCC publication.
  State/type generations and migrations are exposed by `az/state-versions`,
  `az/type-versions`, and `az/stats`; final static Zig has none of these hooks.
- [x] Retain breaking scalar `defn` ABI versions side by side and expose them
  through `az/function-versions`, `az/invoke-version!`, and statistics. A native
  integration test publishes a two-argument v2, invokes it normally, and then
  invokes the retained one-argument v1 in the same process.
- [x] Preserve a same-namespace scalar caller across a dependency-free breaking
  callee signature. If the full new namespace cannot compile because old `B`
  still calls `A@v1`, publish `A@v2` as an independent live slice, retain old
  `B`/`A@v1`, and expose the expected full-source compiler error in statistics.
  After reevaluating corrected `B`, the complete namespace publishes normally.
  This scenario also passes through the asynchronous compiler.
- [x] Generalize breaking-callable live slices to their transitive declaration
  dependencies, cross-namespace callers/SCCs, and versioned
  `defstruct`/`defconst` coexistence through the same inspection machinery.
  Same-module live slices now close over referenced state/type declarations,
  stored declaration references refresh mechanically to current schema cells,
  and breaking `defstruct`/container schemas coexist as retained generations.
  A focused three-namespace acceptance now forces `A@v2` into a partial live
  slice that closes over a two-level local `defconst` chain and an imported
  Zig-only helper. The old local caller, the untouched cross-SCC caller, and
  explicit `A@v1` invocation all remain live until caller reevaluation adopts
  v2. JVM downcall bindings now independently retain their wrapper generation,
  preventing partial publication from closing an arena still referenced by an
  unchanged Clojure Var.
- [x] Make type-producing declarations and containers live-reloadable, not
  merely fingerprinted. This includes `az/container` values, anonymous and
  nested containers, comptime/generic type factories such as TigerBeetle's
  `az/defn OptionsType`, their monomorphizations, and containers returned from
  other comptime declarations. Track their type/schema dependencies and
  recompile/publish the affected dependency SCC atomically. A logic-only,
  schema-compatible reevaluation must update new calls without restarting the
  process; a breaking layout/type change must publish a new version while old
  callers, instantiated types, and live state continue using the old version
  until migration and quiescent retirement. Type references now refresh across
  namespace boundaries with distinct schema and comptime-implementation
  identities. Compatible edits automatically recompile dependent SCCs from a
  coherent staged graph; breaking edits publish only the changed live slice so
  untouched dependents stay on their retained ABI until reevaluated. Retained
  breaking lineages survive compatible follow-up publications, and focused
  same/cross-namespace/cyclic tests prove `u32` → `u64` coexistence, explicit
  caller adoption, and state-capsule migration without unsafe
  reinterpretation. Actual generated TigerBeetle `OptionsType` compatible and
  explicit breaking-`WorkloadType` adoption tests remain green. Forward type
  resolution during component refresh is scoped by logical Zig module, so an
  unqualified name in a large SCC cannot acquire a same-named type from another
  namespace and spuriously turn a compatible edit into a layout break. The
  complete post-fix Kaocha run passes 79 tests and 2,952 assertions.
- [x] Extend direct dispatch to Zig `inline`/`pub inline` declarations where
  the development wrapper can retain call-site optimization around the cell,
  and propagate edits to concrete monomorphized callers for generic/anonymous-
  signature functions that cannot own one stable function-pointer ABI cell.
  Forced-inline functions now keep an inline public wrapper but materialize an
  addressable private implementation per generation; a focused acceptance
  proves an already-compiled caller follows the swapped cell without changing
  generation. Non-dispatchable callable references include implementation
  identity in concrete callers, so a generic cross-namespace edit atomically
  republishes its monomorphized dependent while ordinary pointer-dispatchable
  callees still swap without caller recompilation.
- [x] Preserve the direct static optimized final-build path: `az/source` and
  the `ReleaseFast` source used by `az/build!` contain no `__aguafria_`
  development dispatch symbols.
- [x] Add a project-generic development process host outside the common
  `aguafria.zig` declaration API. It reproduces Zig 0.16 `std.process.Init`,
  runs `pub fn main(std.process.Init) !void` on a native thread, makes the
  host's state capsules authoritative during its lifetime, atomically points
  JVM/native copies at them, copies state back before unloading, refuses two
  simultaneous state owners, and reports host status through `az/stats` and
  `aguafria.zig.host/stats`. Native host threads default to Zig's 16 MiB stack
  size (and expose `:stack-size-bytes`) so normal Zig stack frames are not
  constrained by the JVM's much smaller thread default. Host completion is delivered only after state is
  restored and native arenas close. `aguafria.zig.host/restart!` uses that
  explicit quiescent boundary to start the current generation in the same JVM
  PID and records `:replaces-host-id`; it never force-kills arbitrary Zig code
  or guesses how to migrate live stack/heap objects.
- [x] Expose every supported `az/defn` as an ordinary callable Clojure Var.
  A non-`export` Zig function lazily receives a development-only C ABI
  trampoline on first JVM invocation; later calls reuse the loaded binding and
  final/static Zig source keeps the declaration's original visibility.

### Performance

- [x] Verify compile-once direct Var invocation and fresh-process cache reuse.
  A focused non-`export` Var test proves the first call adds one development
  generation and the second adds none; the real TigerBeetle `:check` reports
  14 finished native builds, 14 cache hits, and zero failed builds.
- [x] Record the initial complete structural TigerBeetle conversion baseline:
  245 files and 3,944 declarations in 27.85 seconds on the current
  macOS/aarch64 development machine with Zig 0.16.0; retain per-file timings in
  the serializable conversion report. After retaining 38 public import Vars,
  the current namespace-native/catalog conversion contains 4,032 declarations
  and completed in 24.32 seconds in the latest full regeneration while resolving
  every project-local nested `@import` through namespace aliases.
- [x] Record a first native behavioral/performance parity sample: deterministic
  VOPR output is byte-identical for seeds 1/42/123/999, while parallel
  handwritten and converted fuzz-smoke runs complete in 23.548s and 23.675s.
- [x] Measure the development process host on real converted VOPR. A cached
  seed-1 100-request run reproduces `PASSED (22666 ticks)` and spends 653 ms in
  the native host; its fresh-JVM wall time is 82.86 s and is dominated by graph
  loading/cold compilation. The live-edit performance run remained active for
  63.452 s while a new generation compiled and published. Nested calls now use
  one per-module active-call lease, and a process host retains all generations
  coarsely so it can omit per-call retirement RMWs without unsafe unloading.
- [x] Add reproducible microbenchmarks and whole-project benchmarks for sample
  and TigerBeetle conversion, catalog bootstrap/Var interning, emission,
  clean/cached/incremental compilation, parallel declaration compilation,
  reload publication, FFM invocation, and dispatch-cell calls. The initial
  `clojure -M:bench` EDN harness now records machine/toolchain metadata,
  clean/same-source/incremental development builds, direct native loops,
  dispatch-cell loops, FFM batches, and runtime timing/cache statistics. Its
  first small ReleaseFast run exposed and fixed development cache suppression:
  Aguafria-owned generated modules are content-addressed and now permit a
  same-hash cache hit, while user-configured mutable module paths remain
  conservative misses. Project modes now benchmark first/repeat conversion,
  cold/warm std catalog installation, first/repeat generated namespace loads,
  and complete emission without touching the checked-in corpus. The measured
  sample is 3 files/11 declarations; TigerBeetle is 245 files/4,032
  declarations and 5.42 MB emitted Zig, always with zero fallback/unresolved
  syntax. Four independent ReleaseFast modules compile 1.44× faster in the
  parallel probe on the recorded machine.
- [x] Reduce interactive compile closure and command size for large graphs.
  Namespace modules are limited to the actual reachable import closure and
  remain content-addressed across builds. Named configured/build-generated
  modules are now selected from the exact imports of the root declaration
  slice plus reachable namespaces, and each is attached only to its importers;
  this also prevents Zig 0.16's unused-`-M` error for small breaking live
  slices. The measured full VOPR graph is 143 modules and 596 dependency edges,
  versus repeating every build option across every namespace. The post-fix real
  signature/state-migration VOPR acceptance passes twice with identical
  `PASSED (10772 ticks)` behavior and zero failed builds.
- [x] Publish timing/cache/queue counters through the existing serializable
  statistics APIs, including per-phase latency percentiles and critical-path
  time for dependency components. `az/stats` now reports overall, per-module,
  and per-purpose min/p50/p95/p99/max/mean summaries for queue wait, native
  build, and end-to-end latency, plus cache observation/hit/miss/rate counters.
  Atomic component publications expose both wall duration and the observed
  parallel critical path; focused monitor/API and SCC rollback tests pass.
  An intentional structural publication stop is reported separately as
  `:migration-required`/`:migration-required-build-count`, not as a compiler
  failure.
- [x] Profile the benchmarks and optimize the measured hot paths using
  content-addressed AST/conversion caches, persistent dependency indexes,
  bounded work-stealing compilation, batched/streamed file I/O, and allocation
  reduction where they materially help. The first profile showed unchanged
  TigerBeetle conversion spending nearly all of its 24.2 s in repeated AST
  extraction, translation, and pretty-printing. Atomic caches keyed by exact
  source, Zig AST helper/toolchain, namespace/import graph, keyword/std
  catalogs, and converter schema now make all 245 AST/render hits explicit and
  cut the unchanged conversion to 3.58 s (6.4×). Corrupt/incomplete cache data
  is discarded and recomputed; focused cold-miss/warm-hit tests pass.
- [x] Define and enforce performance budgets for interactive single-Var reload,
  warm namespace load, complete sample conversion, and complete TigerBeetle
  conversion; record the machine/toolchain with every result. The EDN budget
  catalog covers micro/sample/TigerBeetle modes, and
  `clojure -M:bench check micro|sample|tigerbeetle` exits nonzero with exact
  failed metric paths.
  All 21 current checks pass on macOS/aarch64, Java 23, Clojure 1.12.0, Zig
  0.16.0. The micro baseline is a 45 ms cache hit, 235 ms single-Var reload,
  4.29 ns/dispatch iteration, and 554 ns/FFM call; TigerBeetle's warm
  conversion is 3.47 s and first explicit reload/intern of 4,032 Vars is
  18.35 s.
- [x] Benchmark development dispatch against direct Zig calls, and prove the
  `:reloadable? false` `ReleaseFast` artifact has no dispatch/runtime overhead
  beyond equivalent handwritten Zig. The development probe reports both the
  direct and dispatch loops (4.29 ns per dispatch iteration in the current
  million-iteration run). A separately built final dynamic library contains
  no dispatch/state/epoch markers; its ReleaseFast composed/direct p50 ratio is
  0.96, so the ordinary call is optimized as well as the handwritten loop.

### Host and cross-target portability

- [x] Keep the release compiler target independent of the JVM host: final
  executables/libraries/objects honor Zig `:target`/`:cpu` and contain no JVM,
  FFM, or Aguafria runtime. The TigerBeetle C artifact matrix cross-compiles for
  Linux and Windows from the current macOS/aarch64 host.
- [x] Require development shared libraries to target the current JVM host; the
  same FFM/dispatch design is OS-independent, but a foreign-target DLL/shared
  object is never loaded into the host JVM.
- [ ] Run native compile/invoke, compatible and breaking hot reload,
  active-call retirement, and standalone-build suites on Linux x86_64,
  Windows x86_64, macOS x86_64, and macOS aarch64. Validate platform C ABI
  layouts, dynamic-library naming/loading, native-access flags, and filesystem
  behavior in CI.

### Required live acceptance scenarios

- [x] Define new `A`, redefine already-running `B` to call `A`, and observe the
  new `B` in the same live native process without restart.
- [x] Repeat new-`A`/rewired-`B`, compatible `A`-only swapping, and breaking
  `A@v1`/`A@v2` coexistence across two Clojure namespaces in the same PID.
- [x] Redefine ABI-compatible `A` only and prove existing compiled `B` follows
  the swapped `A` dispatch pointer without recompiling.
- [x] Break scalar `A`'s signature within one namespace and across two
  namespaces; prove old `B` continues through `A@v1`, then publish corrected
  `B@v2` through `A@v2` while both generations coexist.
- [x] Form a real cycle from two ordinary hand-written Aguafria namespaces and
  prove that changing one compatible scalar callee updates the already-compiled
  caller in the other namespace without restarting or recompiling that caller.
- [x] In converted TigerBeetle code, compile an ordinary new Aguafria caller of
  the existing Zig-only `compaction_op_min`, redefine only that callee, and
  observe 96 → 97 → 96 in the same JVM while the caller's implementation
  generation remains unchanged. This proves real converted cyclic-component
  scalar dispatch; it is not the still-pending full running-program/state test.
- [x] Load the complete converted `tigerbeetle.main` graph in a fresh JVM with
  zero failed intermediate builds. Compile an ordinary caller of the real
  converted `stdx.zeroed`, reevaluate only that TigerBeetle Var, and observe
  true → false in the same PID while the caller implementation generation is
  unchanged. This proves non-scalar Zig ABI dispatch and catalog-bounded
  namespace loading; it is not yet the long-lived server/VOPR process test.
- [x] Change a struct layout; prove old code/state remains valid, migrate with
  an explicit function, and route new code to the new version. A 4-byte
  `defstruct` generation remains live after an 8-byte layout is published,
  only reevaluating its dependent redirects that Var to the new schema, and a
  struct-backed `defvar` migration copies the old field, initializes the new
  field, publishes the new capsule, and retains the old generation without
  unsafe reinterpretation.
- [x] Cross a project-generic native-host safe point after a breaking struct
  publication. The active host stays pinned to the 4-byte type, exits and
  restores state, the host root is explicitly reevaluated to adopt the new
  caller graph, and `host/restart!` starts a linked replacement in the same JVM
  PID that observes the 8-byte type. Compatible edits remain immediate and do
  not use this boundary.
- [x] In converted TigerBeetle, reevaluate the comptime `az/defn OptionsType`
  in `src/state_machine/workload.clj` and prove container/type-producing code
  is genuinely hot: compatible changes atomically republish affected
  monomorphizations and dependents, while a breaking generated-container
  change creates a coexisting type generation without invalidating old
  callers or state. The actual generated namespace now passes the compatible
  half in a fresh JVM: changing `pending_timeout_mean` inside `OptionsType`'s
  nested `generate` method preserves its schema fingerprint, advances the
  native module generation, and republishes the containing `WorkloadType`
  generation. A breaking edit now publishes coexisting `OptionsType`
  generations while `WorkloadType` stays on its old schema; explicitly
  reevaluating `WorkloadType` then publishes its coexisting new generation.
  Cross-module and cyclic tests prove automatic compatible propagation,
  explicit breaking caller adoption, retained old ABI invocation, and a
  state-bearing dependent that keeps its old value until `az/migrate-state!`
  publishes `[:retained :migrated]`. Finally, the repeatable
  `clojure -M:vopr-type-hot-acceptance` run changes the actual converted
  `OptionsType` while a 2,000-request seed-1 VOPR is running: the same PID stays
  active, the host is pinned to its complete old dispatch graph, versions are
  `[:retained :breaking]`, VOPR reports `PASSED (10772 ticks)`, exits 0 after
  54.027 seconds, and statistics report zero failed builds. Selectively moving
  that already-live host object graph to the new layout still requires the
  explicit quiescence/migration acceptance below.
- [x] Run the converted TigerBeetle program, make a non-structural logic change,
  evaluate the changed `az/defn`, and prove the same PID adopts it while
  preserving live state. The reproducible
  `clojure -M:vopr-hot-acceptance 2000 1` command starts converted VOPR, defines
  a new state-backed `aguafria_hot_reload_mark`, and reevaluates existing
  `full_core` to call it while the original host stays active. A converted
  performance VOPR stayed `:running` while generation 41 → 45 added
  `aguafria_hot_reload_probe_value` and `aguafria_hot_reload_mark` and rewired
  existing `full_core` to call the new function. The repeatable seed-1 run
  exits 0 with `PASSED (10772 ticks)`, the native probe returns 4,242, the
  process PID is unchanged, and statistics report zero failed builds.
- [x] Perform a versioned function-signature change and a versioned struct/state
  migration in the live TigerBeetle harness without unsafe reinterpretation.
  `clojure -M:vopr-migration-hot-acceptance 2000 1` keeps the original
  converted VOPR running while `full_core@v2` publishes with a third argument
  and the existing `log_performance_mode` requests a `bool` →
  `AguafriaLogPerformanceState` schema. The old host completes with
  `PASSED (10772 ticks)` before migration. At that exact quiescent boundary,
  explicit Zig code copies `enabled` and initializes `migration_generation=1`,
  `main`/`log_override` adopt the new state and ABI, and `host/restart!` runs a
  linked replacement in the same JVM PID. It also reports
  `PASSED (10772 ticks)`, the function has two distinct ABIs with one current,
  state statuses are `[:retained :migrated]`, lineage is 1 → 2, statistics show
  one migration-required safety stop, and failed builds remain zero.
- [x] Run all unit/integration tests through Kaocha from an isolated nREPL and
  publish timing plus conversion/reload statistics. The dedicated
  `:test-nrepl` alias and programmatic Kaocha API run pass 78 tests and 2,948
  assertions with zero failures/errors/pending in 515,680 ms. The same REPL's
  final `az/stats` snapshot reports 293 modules, 4,153 declarations, 165
  dependency components, 135 finished builds, 36 stale superseded builds, and
  zero active/queued/compiling builds or active native hosts. After adding the
  cache, fresh generated-classpath, and migration-monitor regressions, the
  current complete command-line Kaocha run passes 87 tests and 2,986
  assertions with zero failures.

## Deferred ideas from `todo.md`

- A Zig-side comptime EDN reader is complementary, but it is not needed for
  Clojure-to-Zig source generation and native invocation.  Add it when a Zig
  build must consume EDN without a running JVM.
- Flecs batching is unnecessary for the compiler core.  Aguafria already
  batches every declaration in a Clojure namespace into one Zig shared
  library; Flecs can later be a normal Zig dependency used by a game example.
- The circles/text/audio game should follow after package/dependency support
  and aggregate/opaque ABI interop are established.

## Acceptance criteria

- `clojure -M:test` passes, including real Zig compilation and calls.
- Re-evaluating a function updates callers compiled in the same namespace.
- Re-evaluating an ABI-compatible callee updates existing native callers through
  its dispatch cell without recompiling those callers.
- Breaking callable and struct changes create coexisting versions; old callers
  and state remain valid until quiescent retirement or explicit migration.
- The same live behavior works across namespaces and inside a running converted
  TigerBeetle process without restarting that process.
- Generated `.zig` files contain no Aguafria runtime abstraction.
- The public examples in the README run unchanged.
- Every builtin in the installed Zig compiler table has a documented,
  autocomplete-friendly `aguafria.keyword` Var, and catalog drift is detected.
- Every public Zig std declaration is represented by a documented Var in a
  normal require-able `aguafria.std...` namespace after the ordered
  `aguafria.std` bootstrap, including nested containers; there are no generated
  std `.clj` files.
- `az/build!` produces optimized standalone Zig artifacts with no Aguafria
  runtime dependency, including modules imported from external Zig source.
- Generated converted files contain an ordinary `ns` followed only by Aguafria
  declarations. Loading one interns every Var in that declared namespace; no
  generated batch marker or tooling form is permitted.
