# Zig 0.16.0 × Aguafria learning reference

## Objective and acceptance contract

Replicate the complete official Zig 0.16.0 language reference in `examples/learn`,
preserving its HTML layout, CSS, prose, section order, anchors, navigation, Zig
examples and example output. Add independently switchable **Aguafria Zig / Zig**
tabs to each applicable example. This is an executable compatibility project,
not a rewritten introduction or a selection of easy examples.

The goal remains unfinished until the complete inventory has an explicit,
reviewed disposition and all applicable translations pass their intended tests.
Intermediate previews must identify pending examples honestly.

## Pinned sources

- Repository: <https://codeberg.org/ziglang/zig.git>
- Release tag: `0.16.0` (the upstream tag has no `v` prefix).
- Tag object: `44d9672fed001115e674fd5ddb32747ef43a7af4`.
- Peeled commit: `24fdd5b7a4c1c8b5deb5b56756b9dbc8e08c86a8`.
- Initial read-only checkout: `.tmp/zig-learn.VhSOi9/zig` in this repository.
- Template: `doc/langref.html.in`; 292 files in `doc/langref` at this revision.
- Renderer and example harness: `tools/docgen.zig`, `tools/doctest.zig`, and
  `generateLangRef` in upstream `build.zig`.
- Published HTML: <https://ziglang.org/documentation/0.16.0/>.
- Aguafria pin: `resources/aguafria/toolchain/releases.edn`, Zig `0.16.0`.

Record content hashes, upstream revision and retrieval provenance in a lock file.
Keep the working clone ignored under `.tmp`; retain a licensed, minimal reference
snapshot needed to reproduce the learning site. Do not require another example,
an unrelated local Zig installation, or a mutable upstream `master` checkout.
Preserve the Zig MIT notice alongside copied documentation and examples.

## Implementation sequence

1. **Inventory and unchanged baseline.** Read the pinned template, example
   manifests and renderer. Inventory all headings, file-backed code examples,
   inline/multiline syntax snippets, shell/C/grammar blocks and expected outcomes.
   Save the original HTML unchanged and verify section/anchor parity with the
   template. Explicitly account for helper files and multi-file examples.
2. **Learning-site foundation.** Add a small independent `deps.edn` project,
   build/serve/test commands and narrowly scoped CSS/JavaScript enhancements.
   Preserve upstream nodes and syntax highlighting; append accessible tab panels
   without rebuilding the page in a framework. Keep the exported page usable
   offline, with Zig content available when JavaScript is disabled.
3. **Structural translation.** Reuse `aguafria.zig.convert` and the embedded
   compiler's AST parser. Store readable Aguafria forms and per-example reports.
   Use normal `az`/`ak` forms and existing std/package namespace Vars. Preserve
   visibility, types, comptime behavior, errors, tests, comments and dependencies.
   Use reviewed overrides when a teaching example needs clearer presentation.
   No raw Zig source strings count as converted code; reports must detect raw
   declarations, expression fallbacks and unresolved syntax.
4. **Compiler compatibility work.** Turn every real conversion/emission gap into
   a minimal regression in the library, fix it, then rerun the corresponding
   reference example. Preserve existing user changes (including the currently
   dirty emitter and emitter tests). Do not paper over missing language features
   with a `ZIG_ONLY` label or a source-identity bypass.
5. **Behavioral verification.** Reuse upstream doctest directives. Test successful
   executables and Zig tests; check intentional compiler/test/runtime failures
   against their intended failure category, not merely any nonzero exit. Preserve
   target/optimization/backend requirements and supporting files. Compare stable
   output/semantics, accounting explicitly for nondeterministic addresses and
   compiler path/line differences. Separate host-tested, cross-compiled and
   unavailable-target outcomes. Aguafria source must emit the code being tested.
6. **Complete coverage and visual acceptance.** Translate applicable inline code
   as well as file-backed examples. Check all sections and examples, tab keyboard
   behavior, independent selection, deep links, copying, light/dark themes,
   narrow/wide layouts and no-JavaScript behavior in a real browser. A strict
   verification command must fail for missing translations, unreviewed exclusions,
   raw-source fallbacks or failed required checks.

## `ZIG_ONLY` policy

Keep genuinely Zig-source-specific content, such as Zig comment spelling, Zig
lexical grammar and Zig compiler CLI demonstrations, in place with a visible
`ZIG_ONLY` marker and a short explanation. C/assembly/tooling context also stays
present. Where the concept applies to Aguafria (including a snippet containing
comments), translate the meaningful code and explain syntax differences.
Distinguish `pending`, `compiler-gap`, `translated`, and reviewed `zig-only`;
absence of compiler support is never an exclusion reason.

## Structure and commands

Keep implementation cohesive: one build/inventory/verification namespace initially,
one browser enhancement script/style, a translation corpus, explicit overrides,
and focused tests. Split only when responsibilities actually warrant it.

Planned commands from `examples/learn`:

- `clojure -M:build` — reproduce the standalone learning HTML and coverage report.
- `clojure -M:serve` — serve the built site on loopback for local study/visual QA.
- `clojure -M:verify` — strict complete-coverage and executable compatibility checks.
- `clojure -M:test` — deterministic inventory/build/UI-contract regressions.
- A local development/nREPL alias for working on translations/compiler support;
  all native compilation uses `az/zig-executable`, never `zig` from `PATH`.

Generated site/test/cache files are ignored. Keep upstream notices, provenance,
reviewed translations, overrides and tests in Git. No publication or commit is
authorized by this task.

## Evidence required to finish

An auditable coverage manifest with no unexplained holes; exact original heading
order/IDs and Zig panels preserved; every applicable Aguafria panel backed by
real readable forms; expected-outcome compilation/execution reports; regression
coverage for compiler fixes; and inspected browser screenshots plus actual tab,
keyboard, copy and responsive interactions. Reference coverage is strong evidence,
not a mathematical claim to cover every legal Zig program.
