# Aguafria implementation plan

## Status

The core library and source-conversion pipeline are implemented. The current
acceptance suite covers clean-cache native compilation, implicit returns, FFM
calls, parallel snapshots, namespace-module replacement, external Zig modules,
standalone `ReleaseFast` output, statistics, mapped diagnostics, and the
Zig/ZLS-generated keyword catalog plus the complete EDN-derived Zig std
namespace graph. The complete current 245-file TigerBeetle conversion is
checked in and bulk-loadable as 245 ordinary namespaces with 3,982 top-level
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
by their upstream platform/environment guards). The auxiliary local executable
matrix also compiles with `vopr:build`, `fuzz:build`, `vortex:build`, and
`scripts:build`. These changes preserve TigerBeetle behavior and contain no
Aguafria instrumentation. This is not a claim that representative fuzz
workloads, separate language-client tests, cross-platform targets, or the
complete upstream CI matrix have run locally.

The remaining work is larger than the old namespace-module replacement test:
true live native Var indirection, compatible pointer swaps, incompatible ABI
version coexistence, quiescent retirement, state migration, and a running
TigerBeetle equivalence/hot-reload demonstration. These are tracked explicitly
below; this document must not call the project live-complete before they pass.

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
     magical Zig identifiers. Converted modules use cycle-safe Clojure
     `:as-alias` requires and a generated EDN rename catalog; generated
     namespaces contain no Aguafria metadata and no Zig file paths. Clojure
     namespace identity is the live compiler-module identity. Original Zig
     paths remain only in conversion reports used by optional materialization.
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
- [ ] Exercise representative fuzz workloads and the remaining cross-platform,
  language-client, and complete upstream CI matrix without changing
  TigerBeetle behavior.
- [ ] Regenerate `vendor/tigerbeetle-zig-0.16.patch` from the submodule diff and
  verify that it applies cleanly to the pinned upstream commit.
- [ ] Record original/upstream behavioral outputs used for equivalence.

### Conversion correctness

- [x] Parse all 245 TigerBeetle Zig files with Zig's AST and generate one
  ordinary Aguafria Clojure namespace per Zig file.
- [x] Bulk-load all generated namespaces with Vars interned in their declared
  namespaces and zero top-level `az/defraw` declarations.
- [x] Replace converted relative `az/defimport ... []` declarations with
  cycle-safe namespace `:as-alias` requires and real target Vars. Resolve the
  few unavoidable reader/structural name collisions through an EDN catalog,
  keep generated namespaces free of Aguafria metadata and Zig paths, and use
  Clojure namespace identity for live compiler modules.
- [x] Resolve converted source modules (for example `stdx`/`vsr`) as ordinary
  required namespaces. Preserve compiler/build-provided module values such as
  `builtin`, `root`, and build option modules as ordinary `az/defconst` Vars
  initialized with `ak/import`; generated project code contains no empty
  `az/defimport` declarations or invented member stubs.
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
- [ ] Compare normalized generated Zig, executable behavior, protocol-visible
  results, exit status, and selected upstream tests with the handwritten Zig
  baseline.

### Live reload engine

- [x] Add stable logical Var identities plus deterministic callable ABI and
  struct/container schema fingerprints. Store them in Var metadata and the
  serializable statistics API; body/doc changes preserve their compatible key
  while signature/field/order/layout changes produce a breaking key.
- [ ] Generate stable dispatch cells and route reloadable inter-Var calls
  through the cell for the referenced ABI version.
- [ ] Add atomic component publication, active-call accounting, quiescence,
  and safe old-generation retirement.
- [ ] Add cross-namespace dependency graph/SCC handling.
- [ ] Add stable versioned `defvar` state capsules and explicit migration API.
- [ ] Add versioned `defstruct`, `defn`, and `defconst` coexistence and expose
  them through inspection/statistics.
- [ ] Preserve the direct static optimized final-build path.

### Performance

- [x] Record the initial complete structural TigerBeetle conversion baseline:
  245 files and 3,944 declarations in 27.85 seconds on the current
  macOS/aarch64 development machine with Zig 0.16.0; retain per-file timings in
  the serializable conversion report. After retaining 38 public import Vars,
  the current namespace-native/catalog conversion contains 3,982 declarations
  and completes in about 22.7 seconds while resolving every project-local nested
  `@import` through namespace aliases.
- [ ] Add reproducible microbenchmarks and whole-project benchmarks for sample
  and TigerBeetle conversion, catalog bootstrap/Var interning, emission,
  clean/cached/incremental compilation, parallel declaration compilation,
  reload publication, FFM invocation, and dispatch-cell calls.
- [ ] Publish timing/cache/queue counters through the existing serializable
  statistics APIs, including per-phase latency percentiles and critical-path
  time for dependency components.
- [ ] Profile the benchmarks and optimize the measured hot paths using
  content-addressed AST/conversion caches, persistent dependency indexes,
  bounded work-stealing compilation, batched/streamed file I/O, and allocation
  reduction where they materially help.
- [ ] Define and enforce performance budgets for interactive single-Var reload,
  warm namespace load, complete sample conversion, and complete TigerBeetle
  conversion; record the machine/toolchain with every result.
- [ ] Benchmark development dispatch against direct Zig calls, and prove the
  `:reloadable? false` `ReleaseFast` artifact has no dispatch/runtime overhead
  beyond equivalent handwritten Zig.

### Required live acceptance scenarios

- [ ] Define new `A`, redefine already-running `B` to call `A`, and observe the
  new `B` in the same live native process without restart.
- [ ] Repeat `A`/`B` across two Clojure namespaces.
- [ ] Redefine ABI-compatible `A` only and prove existing compiled `B` follows
  the swapped `A` dispatch pointer without recompiling.
- [ ] Break `A`'s signature; prove old `B` continues through `A@v1`, then
  publish corrected `B@v2` through `A@v2` while both generations coexist.
- [ ] Change a struct layout; prove old code/state remains valid, migrate with
  an explicit function, and route new code to the new version.
- [ ] Run the converted TigerBeetle program, make a non-structural logic change,
  evaluate the changed `az/defn`, and prove the same PID adopts it while
  preserving live state.
- [ ] Perform a versioned function-signature change and a versioned struct/state
  migration in the live TigerBeetle harness without unsafe reinterpretation.
- [ ] Run all unit/integration tests through Kaocha from an isolated nREPL and
  publish timing plus conversion/reload statistics.

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
