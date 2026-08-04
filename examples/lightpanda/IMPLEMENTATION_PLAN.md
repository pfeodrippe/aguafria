# Lightpanda Aguafria implementation plan

The example must prove two separate outcomes:

1. an ordinary standalone Lightpanda browser built from Aguafria-generated
   sources, with no JVM or development runtime in the artifact; and
2. a persistent nREPL workflow in which real Lightpanda Zig declarations can
   be inspected, called, changed, compiled, and installed without restarting
   the JVM.

The vendored Lightpanda checkout is input only. Once generation has completed,
materialization and standalone builds must obtain every Zig source and bundled
project asset from `generated/`.

## Repository and baseline

- [x] Add `vendor/lightpanda` as the official Lightpanda Git submodule.
- [x] Keep `vendor/` and `generated/` inside `examples/lightpanda`.
- [x] Confirm the pinned upstream revision declares Zig 0.16.0 support.
- [x] Build pristine upstream Lightpanda in ReleaseFast mode.
- [x] Run the pristine binary and verify its version plus a real browser
  operation before Aguafria conversion.
- [x] Make only Zig 0.16 compatibility changes in the submodule if the pristine
  build proves they are necessary; never change browser behavior to satisfy
  Aguafria. No changes were necessary at the pinned revision.

## Complete conversion and standalone equivalence

- [x] Add a clean Clojure project whose generation namespace loads before any
  generated output exists.
- [x] Convert every project-owned `.zig` file structurally, with zero raw
  declarations, unresolved forms, or parser fallbacks.
- [x] Bundle all non-Zig inputs required to reconstruct the project.
- [x] Store generated namespaces, the project catalog, assets, and conversion
  report under `generated/`.
- [x] Materialize an ordinary Zig project using only generated Aguafria data
  and bundled assets, never the vendored Zig source tree.
- [x] Build the materialized project in ReleaseFast mode and verify version,
  browser behavior, and artifact identity independently of the JVM.
- [x] Record materialization/build commands and bounded reports in the example
  API and README.

## Persistent-JVM development and hot reload

- [x] Expose generated declarations as ordinary Clojure Vars that compile on
  first native use and reuse cached artifacts afterward.
- [x] Add an example namespace with one-comment setup, inspection, invocation,
  editing, restoration, and shutdown workflows.
- [x] Keep a real Lightpanda native session alive while applying compatible
  declaration updates.
- [x] Verify a simple leaf-body edit changes behavior without changing the
  native session identity.
- [x] Verify a medium cross-namespace edit recompiles the affected dependency
  closure and changes a real browser-visible result.
- [x] Verify a complex comptime/container/type-producing edit installs a new
  compatible version while existing native state remains safe and inspectable.
- [x] Verify breaking layout/signature changes use versioned native artifacts;
  old users remain valid until callers/state are explicitly migrated.
- [x] Report compilation state, generations, cache hits, dependency fan-out,
  timings, active artifacts, and errors through the public statistics API.
- [x] Confirm failed edits produce source-located, Rust-style diagnostic data
  at the nREPL and leave the last working native generation installed.

## Four-project acceptance matrix

- [x] Run the Lightpanda example tests and all three hot-reload levels in one
  persistent JVM.
- [x] Re-run Simple Game standalone, web, and persistent-JVM hot reload.
- [x] Re-run Ghostty standalone and persistent-JVM hot reload.
- [x] Re-run TigerBeetle standalone and persistent-JVM hot reload.
- [x] Run Aguafria's complete Kaocha suite after any shared runtime/converter
  change.
- [x] Record measured cold and warm timings; optimization must be general and
  must not add JVM/dev machinery to standalone or web artifacts.

## Recorded Lightpanda results

- Complete conversion: 525 Zig files, 16,267 structural declarations, 512
  bundled assets, and zero raw declarations, fallbacks, or unresolved syntax.
- Generated-only materialization: 1,037 files (525 Zig + 512 assets).
- ReleaseFast standalone: 73,615,608 bytes on macOS arm64; exact version
  `1.0.0-dev.8500+e1435339`; JavaScript/DOM fetch produced `Lightpanda-42` in
  0.15 seconds wall time.
- Cold full development browser compile after a shared wrapper invalidation:
  119.6 seconds; the native fetch then completed in 91 ms.
- Warm edit-to-observable publication in one JVM: simple leaf 69–70 ms,
  converted function 100–630 ms, and real comptime/type factory 793–1,181 ms.
  All cases restored the original declaration and retained the same native
  state address.
- Error diagnostics remap dependency cache locations to the originating
  Aguafria declaration and Clojure file/form, while keeping the full compiler
  command in exception data rather than flooding the primary message. The
  final live probe identified `diagnostic.user-source/broken-value` at its
  Aguafria source location even though Zig reported a content-addressed
  dependency file.
- Final four-project acceptance passed 13 example tests with 87 assertions.
  Simple Game rebuilt its ReleaseFast behavior probe and desktop artifact plus
  its `wasm32-emscripten` web package; Ghostty materialized 5,836 generated-only
  files and rebuilt `libghostty-vt` ReleaseFast; TigerBeetle loaded 2,803
  declarations and ran its real version host; and Lightpanda's standalone
  browser again produced `Lightpanda-42`.
- The final root Kaocha run passed 125 tests and 3,171 assertions. A static
  dependency export-collision found by the acceptance pass was fixed generally:
  implicit REPL exports become namespace-public in standalone dependencies,
  explicit ABI exports remain exported, and no development container enters
  static output.
