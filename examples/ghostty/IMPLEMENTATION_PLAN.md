# Ghostty Aguafria implementation plan

This example applies the general Zig → Aguafria Clojure → Zig workflow to
Ghostty. Generated namespaces are ordinary structural Aguafria code—there are
no raw Zig declaration strings—and they can rematerialize a standalone project
without reading the original Ghostty `.zig` files.

## Host and upstream baseline

- Host: macOS 15.1 arm64, Xcode 16.1 (SDK 15.1), Java 23, Zig 0.16.0.
- Upstream: `ghostty-org/ghostty` commit
  `6687d6089dc254b14b1cdb22ca310f8394c3290f`.
- Compatibility branch: `aguafria-zig-0.16` in
  `examples/ghostty/vendor/ghostty`.
- Current Ghostty `main` already requires Zig 0.16.0.

- [x] Add Ghostty as `examples/ghostty/vendor/ghostty`, pin the upstream
  revision, create the compatibility branch, and inspect its repository
  instructions.
- [x] Build the unmodified Zig core with Zig 0.16.0. The cold Debug build took
  94 seconds and produced static and dynamic `libghostty-vt` artifacts.
- [x] Run the unmodified upstream `libghostty-vt` matrix. Its 2,504-test and
  2,889-test configurations passed in 5 minutes 52 seconds cold.
- [x] Make behavior-preserving Xcode 16 compatibility changes only in the
  submodule branch: remove Swift 6.2-only trailing-comma syntax, compiler-gate
  macOS 26 glass APIs, make the main-queue image transfer explicitly Sendable,
  and split one equivalent SwiftUI expression for Xcode 16's type checker.
- [x] Build the complete native application on this host. The warm upstream
  command completes in 6.35 seconds.
- [x] Open the produced application and exercise its terminal window through
  macOS UI automation. The standalone `Ghostty.app` accepted a real terminal
  command and visibly rendered `AGUAFRIA_GHOSTTY_STANDALONE_UI_OK`.

## Generated project

- [x] Create the normal `deps.edn` project under `examples/ghostty`, including
  generation, materialization, nREPL, test, ReleaseFast VT, and macOS app aliases.
- [x] Convert all Ghostty-owned Zig into a stable `ghostty.*` namespace graph
  and an EDN project catalog.
- [x] Bundle ordinary non-Zig resources, project-relative symlinks, nested
  `build.zig.zon` files, generated option modules, and anonymous generated Zig
  modules needed by the selected build profile.
- [x] Keep dependencies such as HarfBuzz as native Zig package dependencies;
  they do not need conversion to make Ghostty-owned code reloadable.
- [x] Keep project imports as ordinary namespace aliases and all declarations
  as directly referable Clojure Vars.
- [x] Add focused general converter fixtures for Ghostty-exposed inline asm,
  `nosuspend`, exact identifiers, nested extern containers, and build-generated
  anonymous module capture.

The verified conversion contains 764 Ghostty-owned Zig files, 13,832
structural declarations, five build-generated modules, and 5,072 bundled
assets. It reports zero `az/defraw`, zero fallbacks, and zero unresolved syntax.
A warm full-tree generation takes about 18.6 seconds.

## Development host and hot reload

- [x] Expose a same-JVM `libghostty-vt` terminal session through Java FFM. The
  terminal handle and state are inspectable Clojure values; no helper process
  is used.
- [x] Expose direct calls into converted Ghostty through ordinary Aguafria Vars.
  The representative focus encoder returns byte 73 (`I`) for focus gained and
  byte 79 (`O`) for focus lost.
- [x] Demonstrate a compatible edit in converted Ghostty itself: changing its
  focus byte from `I` to `X` changed the Clojure result from 73 to 88 while the
  existing terminal retained the exact same native address.
- [x] Restore that declaration in the same JVM and observe 73 again.
- [x] Define a new native function A, reevaluate existing function B to call A,
  and publish result 42 into the title of the existing terminal.
- [x] Record publication behavior: a first large-graph edit took 1.35–1.41
  seconds; an already-cached compatible restore took 122 ms. The direct
  generated function takes about 3.15 seconds in a fresh artifact-cache JVM and
  is immediate on repeated calls in the same JVM.
- [x] Document ABI behavior: body-compatible edits replace the dispatch target
  for existing callers and state. Signature or layout changes create a new safe
  version; old native objects retain their old ABI until dependents publish and
  the application performs any required state migration.

## Standalone behavior

- [x] Rematerialize 764 Zig files and 5,072 assets exclusively from generated
  Aguafria namespaces/catalog resources. The resulting 5,836-file tree has no
  path dependency on `examples/ghostty/vendor/ghostty`.
- [x] Build `libghostty-vt` in ReleaseFast with no JVM/Clojure dependency. The
  cold build took about 30.6 seconds and a warm build takes about 0.5 seconds.
- [x] Run the full upstream `libghostty-vt` test matrix against the rematerialized
  project. It passed again in 257.2 seconds.
- [x] Run Ghostty's complete `zig build test` step against the rematerialized
  project. All 86 build steps succeeded: 3,284 tests passed and 16 were skipped
  (3,300 total), with exit status 0.
- [x] Build the complete universal `Ghostty.app` from the rematerialized tree.
  It completed in 81.57 seconds and contains arm64 and x86_64 executables.
- [x] Keep development inspection and dispatch out of standalone artifacts;
  their generated Zig follows Ghostty's native build graph directly.

## Verification

- [x] Focused converter fixtures pass, including generic generated-source capture.
- [x] Example Kaocha suite passes: 3 tests, 20 assertions.
- [x] Same-JVM nREPL native-state and hot-publication walkthrough passes.
- [x] Upstream and rematerialized VT matrices pass.
- [x] Complete rematerialized Ghostty project suite passes: 3,284 tests, with
  the 16 platform/configuration skips reported by Ghostty.
- [x] Upstream and rematerialized native application builds pass.
- [x] Run the complete Aguafria Kaocha suite: 108 tests, 3,099 assertions,
  zero failures.
- [x] Complete actual UI input validation in the standalone macOS application.

## Completion criteria

The code-generation, same-JVM development, hot-reload, standalone VT,
standalone macOS app build, and actual UI-input goals are complete.
