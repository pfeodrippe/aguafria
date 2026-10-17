# Learn Aguafria Zig

The complete [Zig 0.17.0 language reference](https://ziglang.org/documentation/0.17.0/),
with its original HTML, sections and output preserved and per-example Zig/Aguafria
tabs. Applicable file examples, contextual blocks and inline equivalents are
hand-written and checked against the pinned reference.
This is not a claim of complete language compatibility.
See [AGENT_TODO.md](AGENT_TODO.md).

The published reference is not rewritten: prose, Zig code, Shell commands and
output stay verbatim. Only Aguafria alternatives, their recorded REPL output and
language-switching controls are added. Removing these additions must recover the
complete original HTML byte-for-byte; a regression test enforces this.

**Side by side** is the default: Zig/Shell appears left of Aguafria/REPL, whose
column starts at the window midpoint. Both columns retain their individual
width. Tabs stay aligned with the prose while the panels scroll horizontally
on smaller windows. Code and output areas independently match the taller
height, including individual tabs.

Use `?view=zig`, `?view=clj`, or `?view=side-by-side` to choose the initial view
for all examples. Individual tabs remain independent; a direct panel anchor
selects that panel. **Contents** opens a collapsible sidebar with expandable
sections, without moving the page. Escape closes it and returns keyboard focus.

Each authored file ends in a `comment` containing
its reproducible calls. Load the namespace, then evaluate the forms inside that
comment individually (evaluating the whole `comment` intentionally does nothing).
Examples call their own `main`, named test, or helper Vars directly in the JVM.
The comment includes any required argument/memory setup. A public `:!void` main
taking `std.process.Init` or `Init.Minimal` can be called as `(main)` or
`(main ["arg"])`; Aguafria supplies the native startup context inside the same
JVM. Ordinary functions and C-exported main still need their declared arguments.
Named tests also execute in-process through Panama, not a child runner.
REPL panels record those exact comment forms, their output and return values.
Addresses and execution timings naturally vary between runs.

Context-only declarations do not invent an entry point. Platform-specific and
deliberate panic/exit examples need care: a native panic can terminate the JVM.
Their original Shell results are not copied into a pretend REPL transcript.

### WebAssembly and WASI

The four Wasm/WASI lessons use their real target in the final comment, rather
than attempting to call a Wasm export or a WASI entry point through JVM FFM.
Evaluate the comment forms to build with the pinned Zig compiler and run via
`clojure.java.shell/sh` and Node.js (tested with Node 22). The host programs in
`resources/learn/host/` use only Node built-ins; no npm install is needed.

| Lesson | Target and actual output |
| --- | --- |
| `math` | `wasm32-freestanding`, exported `add`, imported `env.print`: `The result is 3` |
| `wasi-args` | `wasm32-wasi`: `0: wasi_args.wasm`, `1: 123`, `2: hello` |
| `wasi-preopens` | `wasm32-wasi`: `info: 0: stdin` through `info: 3: .` |
| `test-wasmMemoryGrow-builtin` | Real wasm32 WASI test executable: `All 1 tests passed.` (not a native-host skip) |

Only the preopens recipe grants the current directory to the module. Node WASI
is not a security sandbox. The WASI host's experimental warning is suppressed;
program stderr and nonzero exit statuses are still reported.

The memory-growth lesson explicitly converts the previous `usize` page count
to `isize` with a checked cast: Zig 0.17's grow builtin can return `-1`. The pinned
upstream test compares the two types directly and fails to compile when actually
targeted at wasm32. Its original file remains untouched. The regression checks
that original failure and runs a temporary corrected upstream copy, alongside
exact output comparisons for the other three unmodified upstream programs.
`learn.wasm-examples-test` executes the actual comment forms in a fresh JVM and
temporary working directory. These are target-specific results, not native-JVM
prepared-execution passes.

From this directory:

Type tooltips require ZLS matching Zig 0.17.0. Set `AGUAFRIA_ZLS` to its
executable (otherwise `zls` is resolved on PATH). See the root
[migration report](../../ZIG_0_17_0_MIGRATION_2026-10-03.md#zls-for-zig-0170)
for the verified upstream revision and build instructions.

HTML builds and translation/output verification automatically format modified
examples with the pinned [cljfmt](https://github.com/weavejester/cljfmt) alias
and local Aguafria indentation configuration. This includes staged, unstaged,
and untracked `.clj` files under `resources/learn/example`; deleted files are
skipped. Git or formatter errors stop the build. To format or check manually:

```sh
clojure -M:fmt fix resources/learn/example/test_structs.clj
clojure -M:fmt check resources/learn/example/test_structs.clj
```

Formatting runs before source fingerprints are calculated. A formatting change
invalidates old execution evidence normally; building HTML alone does not
rerun native outputs. Use `:outcomes` before `:build` to refresh that evidence.
The original Zig documentation and examples are not formatted.

```sh
clojure -X:prepare         # prepare local Aguafria before starting the REPL
clojure -M:translate       # regenerate displayed namespaces and emitted Zig
clojure -M:blocks          # handwritten blocks; check syntax and shared native fixtures
clojure -M:inlines         # check short forms; compare closed expression outputs
clojure -M:outcomes        # compare original + Aguafria outcomes; fail on mismatch
clojure -M:outcomes 1      # sequential verification (default: 4 virtual build workers)
clojure -M:build           # offline build/site/index.html, inventory and coverage
clojure -M:serve           # http://127.0.0.1:8096/
clojure -M:test            # snapshot/build/evaluation regressions
node --test test/highlight_test.cjs # exact-text Clojure highlighting regressions
node --test test/layout_test.cjs    # browser layout regressions (Playwright + Chrome)
node --test test/type_tooltip_test.cjs # Zig-tool hover, keyboard and source-span checks
clojure -M:verify          # fail for missing, stale or mismatching evidence
clojure -M:dev:nrepl       # dedicated local development REPL
```

The Learn `:precompile` alias has no hardcoded namespace exclusions. Compiler
errors discovered during preparation reject the affected namespaces automatically;
working namespaces still produce a bundle. Rejected namespaces and their original
reports are retained under `:namespace-admission` and `:rejected-preparation`.
Source errors, namespace-loading failures, unsupported JVM planning, and normal-JVM
compilation failures are distinguished rather than all being called invalid source.
Generic `a/precompile!` also accepts explicit `:ignore [namespace ...]`; ignored
selections are reported separately, never counted as prepared operations.

Prepare every namespace in Learn's example directory explicitly:

```sh
clojure -J-Xmx1536m -X:precompile :source-dirs '["resources/learn/example"]' :parallelism 16
```

Native artifacts are shared under `~/.aguafria/zig`; the coverage report remains
project-local at `.aguafria/precompile/report.edn`. Preparation compiles and
analyzes the examples without running their native function or test bodies.
After source discovery, bounded compiler admission checks the exact ordinary JVM
adapter and namespace-image graphs, including code generation and linkage. Failed
graphs reject their requesting namespaces before the final bundle build. Shared
artifacts are retained when a surviving namespace still needs them. Admission uses
temporary native images without loading them or publishing cache indexes.

The surviving artifacts are validated and linked into one final bundle. Reports
distinguish pending validation from prepared handlers and include both
`:namespace-admission :validation` and `:bundles :validation`. Unexpected final
bundle failures still abort without publishing a partially validated bundle.
Source analysis uses a cached, bounded native tokenizer, not example execution.
Type queries, admission checks, support tools and final bundling all count in
`:compiler-work`; the whole preparation is not a single compilation. Native test
runners are not prepared, although their body operations participate in discovery.

Then verify ordinary JVM evaluation against that exact bundle. Each command
starts a fresh JVM:

```sh
clojure -M:dev -m learn.values-cache-check .aguafria/precompile/report.edn
clojure -M:dev -m learn.bundle-cache-check .aguafria/precompile/report.edn
```

The Values gate evaluates all five actual bodies and compares their output with
native `main`. The broader gate evaluates 80 top-level bodies from 37 safe
lessons, three constants and 12 native test owners; it does not run all Learn
subforms. Both reject new native builds, standalone JVM handlers and any pack
other than the producer's single bundle. The broader gate also observes native
testing expectations when a body discards their results. Deliberate panic/exit
examples remain isolated in the complete audit.

### Full editor-equivalent prepared check

The narrower gates above are not proof that every buffer works. Use the public
`aguafria.zig.check/check!` workflow for the complete Learn source inventory:

```sh
# Run from examples/learn, after the producer above. Use a NEW report directory.
clojure -J-Xmx1536m -X:editor-check \
  :prepared-cache-dir "\"$HOME/.aguafria/zig\"" \
  :precompile-report '"/absolute/producer/report.edn"' \
  :report-dir '"/absolute/new/editor-check"' \
  :parallelism 4 :heap-mib 768 :timeout-seconds 120

clojure -J-Xmx768m -X:editor-check-report \
  :check-dir '"/absolute/new/editor-check"' \
  :precompile-report '"/absolute/producer/report.edn"' \
  :output-file '"/absolute/LEARN_EDITOR_CHECK.md"'
```

`learn.editor-check` supplies scenarios to the existing checker; it is not a
separate evaluator backend. Each namespace gets a fresh JVM. The worker loads
the actual source file, then the workload uses `aguafria.zig.repl/plan`, the same
initializer type-context cast and `explain!` wrapper as `aguafria-eval-buffer`,
and prints each whole form's result. It stops at the first exception. There are
no native-test substitutions, calls to `main`, extra scope wrappers, or assertion
instrumentation. Returned error-set constants are ordinary values, not failures.

The existing cache is reused without another producer. Strictness includes
**namespace loading**, not only body evaluation. Compiler attempts are blocked
and recorded; standalone disk-cache fallbacks fail the audit. Events inside
nested `explain!` reports are also recorded, along with bundle IDs. Process exits
and timeouts retain the last checkpoint. Native compiler processes need an
aggregate resource guard in addition to the per-JVM heaps; the full cold audit
used an 8 GiB process-tree limit.

Read `report.edn`'s **`:accepted?`**, not just the CLI exit code. Precompilation can
finish successfully while rejecting namespaces or leaving unsupported demands.
Intentional negative examples, target-specific examples and planner skips remain
in the report, not an exclusion list. `partial`/`not-exercised` coverage is not a
full-body pass. Fixes must survive a fresh isolated cache and producer/check cycle;
consumer compilation must not silently warm away missing preparation.

Reviewed intentional negatives live in `learn.editor-check-expectations`. They
match a specific load/evaluation stage, root exception class, diagnostic pattern,
and identifying exception data through the public checker's `:expect :exception`
contract. They are reported as `expected-negative` coverage, not successful body
completion. Compiler-policy failures and disk fallbacks never satisfy those
contracts. A producer rejection alone is not an expected editor outcome; missing
prepared rejection support remains a failure. The constant-reassignment lesson
now has a narrow exact ordinary-build rejection receipt: the producer verifies
its real initial-load build, while consumers validate source/compiler/configuration
identity and reproduce the mapped diagnostic without compiling. Inspection
rejection or a blocked compiler attempt cannot substitute for this proof.

The allocator-leak lesson uses genuine resource-owned native images. Its exact
editor form can allocate and check the list without running a Zig test runner.
Such form completion is **not leak detection**: the intentionally outstanding
allocation is not torn down or reset for the sake of the checker.

### Repair in HTML-order batches of 50

Use the rendered page as the order authority, not alphabetical namespace order.
`editor-prepare` delegates to public `precompile!`; `editor-check` still delegates
to public `check!`. Both accept `:html-file`, `:batch N`, and `:batch-size 50`.
They record the page hash, every selected source hash, and the full ordered
inventory. Named Clojure snippets displayed in the page are included. Repeated
captions use their first occurrence and are recorded; missing or ambiguous source
mappings fail rather than silently skipping an example. The HTML supplies order;
the checker evaluates the current source files, not stale displayed code.
Preparation supplies those absolute paths through public `:source-files`, so
initial source loading preserves the same diagnostic identity as the editor.
Already-loaded dependencies are not replayed to manufacture matching receipts.

When the selection includes `float-mode-exe`, preparation builds the companion
`float-mode-obj` using its authored fast/PIC object recipe, in compile-only mode.
It records the artifact hash, command and per-module linker configuration in
`<precompile-report>.linkage.edn`. Both workers receive that configuration before
project loading; the consumer verifies the object hash and never rebuilds it.
The object/executable linkage contexts are isolated from the ordinary shared
pack. The adjacent receipt is optional for historical reports that predate this
feature. It is not a warm-up call to `main` or execution of the comment form.

```sh
# From examples/learn. RUN must be a new absolute directory; no shared-cache deletion.
RUN=/absolute/new-batch-01-run
mkdir -p "$RUN"
python3 ../../dev/aguafria/resource_guard.py --max-mib 8192 --timeout 1200 \
  --report "$RUN/prepare-guard.json" -- \
  clojure -J-Xmx1536m -J-Daguafria.cache-dir="$RUN/cache" -X:editor-prepare \
  :html-file '"build/site/index.html"' :batch 1 :parallelism 16 \
  :report-file "\"$RUN/precompile.edn\""

python3 ../../dev/aguafria/resource_guard.py --max-mib 8192 --timeout 1200 \
  --report "$RUN/check-guard.json" -- \
  clojure -J-Xmx1536m -X:editor-check \
  :html-file '"build/site/index.html"' :batch 1 \
  :prepared-cache-dir "\"$RUN/cache\"" :precompile-report "\"$RUN/precompile.edn\"" \
  :report-dir "\"$RUN/check\"" :parallelism 1 :heap-mib 768 :timeout-seconds 120
```

Keep consumer parallelism at **1** for literal top-to-bottom execution. Fix the
current batch, rerun it, then replace `:batch N` with `:through-batch N` in both
commands to prepare and recheck every earlier batch together with it. Do not
advance merely because the producer or CLI exited successfully. Review checker
acceptance, actual form coverage, and intended-negative diagnostics; a blocked
compiler is never the expected diagnostic. Retain every failed iteration's
receipts. `:batch` and `:through-batch` are mutually exclusive.

`serve` reuses the existing HTML snapshot; restarting it does not invalidate
recorded REPL output. After compiler or example changes, rerun the verification
steps above before rebuilding the page. A first start without HTML builds it.

Translation and outcome verification are incremental: unchanged lessons reuse
their emitted code and captured REPL output. Editing a lesson, an imported local
helper, or an embedded input invalidates that lesson and its dependants. Missing
or changed generated artifacts and previous failures are retried. Compiler,
toolchain, dependency configuration, or shared verifier changes invalidate the
cache. Unchanged blocks and the batched inline-expression check are reused too;
assembling the HTML remains a separate, inexpensive step.

Independent native compiler/harness processes run on a bounded pool of virtual
threads (1–32 workers). Actual comment forms still execute in-process; namespace
loading and native output capture are serialized so one lesson cannot consume
another lesson's output. Results retain documentation order regardless of finish
order. `verify-outcomes!` also accepts `examples` and `{:jobs n}` from the REPL.

Container methods use `(a/fn name return-type ... [args] body...)`, public by
default, or `a/fn-` for private methods. Named container values use ordinary
constructor calls such as `(Timestamp {:seconds 0 :nanos 0})`; explicit `a/init`
remains useful for anonymous/computed type expressions and non-container schemas.

The example uses the library checkout so compiler fixes can be tested immediately.
All native builds use **Aguafria’s embedded Zig**, never a `zig` found on PATH.
No other example is a dependency. Normal builds work from the hash-locked,
MIT-licensed snapshot and do not download the reference again.
Each lesson can be loaded directly in a fresh REPL. Nested std namespaces have
their own prepared classpath entry points under Aguafria's ignored `generated/`;
no preliminary `[aguafria.std]` import is needed.
Compiler-provided values use `[aguafria.builtin :as builtin]`, for example
`builtin/is_test`. This is Zig's `@import("builtin")`, distinct from
`std.builtin`; its values follow the consuming compilation's mode and target.
Error-returning functions use `:!void`, `:!u32`, or `:!MyType`; composite payloads
use `[:! payload-type]` (equivalent to `[:error-union payload-type]`).

Calling an `a/defn` or imported function Var executes native Zig from ordinary
Clojure or Java. Generic/comptime calls are specialized natively. For example,
`(debug/print "Hello, {s}!\n" ["World"])` prints and returns `nil` (Zig `void`),
while `(math/sqrt 9.0)` returns `3.0`. Bound Clojure output writers receive
stdout/stderr from synchronous calls, including CIDER's output buffer.
Use a quoted form with `a/emit-expr` when you want source instead of execution.

Generic container methods also have ordinary, completable Vars. Require
`[aguafria.std.ArrayList :as array-list]`, then call
`(array-list/append list allocator \☔)`. This is equivalent to
`((a/field list :append) allocator \☔)`, in native code and from the JVM.
The method mutates the original `k/var` receiver, not a copied list. Use
`array-list/deinit` with the same allocator to release the list's allocation;
closing the native value handle alone only releases its container storage.
Fields have corresponding getters: `(array-list/-items list)` and
`(array-list/-capacity list)`. These read the current receiver from the JVM and
compile to direct field access in native code, with field docs and `[self]` metadata.
Nested aliases expose their fields too:

```clojure
(require '[aguafria.std.ArrayList :as al]
         '[aguafria.std.ArrayList.Slice :as al-slice])
(-> list al/-items al-slice/-len)
```

This still emits `list.items.len`. Type docs list the available getters and
field signatures qualify known types, such as `aguafria.std.ArrayList/Slice`.
`ArrayList.Slice` names ArrayList's own slice alias, not a separate interface.
Its getters accept compatible slice receivers, but use the alias belonging to
your API rather than importing ArrayList solely to access an unrelated slice.
The same catalog enrichment applies to registered packages during preparation.
Unknown/comptime-dependent type expressions do not acquire guessed fields;
conditional types expose only members established in every branch.
Pointer getters return borrowed typed addresses: keep the backing storage alive,
and do not use a pointer after its storage is resized or freed.
Prepared, ignored namespace files expose declarations and documentation for
static editor tooling; the live Vars also expose normal `:arglists` and `:doc`.
`testing/allocator` is backed by a persistent native test context when used from
the JVM. Native `a/deftest` execution still owns its usual leak-checking scope;
an ordinary Clojure `let` does not itself create a native test scope.
Thus a successful append/length check is not a promise that allocations were
freed. Failed native tests retain their full Zig trace and add mapped Clojure
source locations/forms, also available in the exception data.

Runnable declarations must define their dependencies before use. Unknown names
are rejected before registration, including generic functions and batch loading.
Incomplete documentation snippets remain syntax-only excerpts; they do not gain
invented declarations or runnable entry points.

Native handles print explicitly, for example
`#aguafria.zig.value.ZigValue[{:items [], :capacity 0}]`. Dereference (`@list`)
or `a/value` returns the decoded view without the tag. Inspection uses current
native fields, including generated generic structs; unbounded pointer fields
display their addresses/types without being dereferenced. The tag is for
inspection, not a readable serialization of native ownership.

Teaching examples are hand-written under `resources/learn/example/`, with
`learn.example.*` namespaces and Zig-derived filenames (`test_if.zig` becomes
`test_if.clj`, declaring `learn.example.test-if`). They use normal `let` bindings,
meaningful names and named test Vars. The converter provides drafts for
comparison, not finished lessons: strict acceptance rejects drafts even when
their native tests pass.
Spaces, dots and hyphens in upstream basenames become underscores, so every
Clojure file matches normal `require` lookup. Namespace symbols use hyphens:
`Assembly_Syntax_Explained.clj` declares `learn.example.Assembly-Syntax-Explained`.
`resources/learn/overrides.edn` maps authored sources and narrowly justified
`ZIG_ONLY` exceptions. Generated namespaces, emitted Zig, test reports
and the site live under ignored `build/`. The native harness evaluates the exact
displayed Clojure source, collects its declarations without loading native code,
and compiles the result. Some upstream cases only check syntax or compilation;
cross-target results must not be mistaken for host execution.
Outcome reports also compare actual output or semantic diagnostics and include
source/compiler fingerprints. Stale evidence is not shown as verified. Diagnostic
comparison ignores stack locations, not the reason for an intentional failure.
The comparison is generic: the original execution supplies the expected output,
not a hand-written answer per example. `:outcomes` saves all reports and fails if
any comparison differs, even when both programs exit successfully. One reviewed
exception checks the shape and nonzero value of an allocated pointer, since
independent processes cannot be expected to allocate at the same address.
`@compileLog` payloads are compared exactly, including their values and types.
Test symbols supply their native names; no duplicate test-label metadata is
needed. Comparisons pair AST-derived runner labels by declaration order while
preserving test counts, results and printed data. REPL transcripts retain the
actual symbol-derived names, not the comparison's normalized labels.
The 15 larger explanatory Zig blocks live under `resources/learn/snippet/`,
with `learn.snippet.*` namespaces. Eight have shared native fixtures:
the same inputs and supporting functions run the original and emitted code,
comparing their real output. Six omit required context and receive syntax checks
only; these are not reported as executed. An intentionally incomplete function
stays incomplete, leaving Zig to diagnose its missing return rather than
inventing a value.
The file-discovery block additionally checks imports under test filters and
native/cross-target compilation. Its known empty-test-count difference is recorded
explicitly; meaningful test identities and their actual output must still agree.
C, JavaScript and PEG blocks remain unchanged, with contextual `ZIG_ONLY` notes.

From the dedicated REPL, require a lesson and call its test directly:

```clojure
(require '[learn.example.test-integer-pointer-conversion
           :refer [integer-pointer-conversion-test]])
(integer-pointer-conversion-test)
```

This runs the selected native Zig test, prints its real output, and returns a
compact result. A failing test throws with native diagnostics. Reevaluate the
declaration and call the same Var again to test the edit. No local Zig install
or extra wrapper is needed.

To run a complete reference case, including its upstream outcome checks:

```clojure
(require '[learn.reference :as learn])
(learn/run-example! "hello.zig")
```

This evaluates the displayed Aguafria namespace and invokes the pinned native
harness using embedded Zig. It is not a JVM interpreter for Zig. The **REPL**
panel records the file's actual comment calls, output, result or exception; it never copies
the original Zig **Shell** output. Each output stays with its language tab.
The result distinguishes native execution, compilation-only checks, cross-target
builds and expected failures. Explanatory fragments are not presented as runnable
programs when their surrounding definitions are missing.

Short type/keyword/name references have individual **⇄** language controls. Their
tooltips distinguish real keyword Vars, type-position syntax and names supplied
by the surrounding example. These mappings are not execution tests. Contextual
expression translations are checked through the real emitter. All 1,362 inline
occurrences now have a translation or explanation. Seventy-seven distinct closed
expressions also run against the original Zig values; context-dependent syntax is
not treated as an executable program. `build/inline.edn` records each occurrence
and its verification scope, without adding status chatter to the lessons.
Obsolete upstream library paths are explained, not replaced with invented Vars.
The upstream pointer-alignment assertion fails on both sides with the pinned
compiler; a regression checks that specific diagnostic rather than calling it a
successful example.
The published HTML and tagged template differ at one inline function name; both
spellings and the discrepancy are preserved in the coverage report.

Clojure highlighting uses a pinned, locally bundled Prism grammar, including
metadata and character-literal handling. It works offline and preserves exact
source when copied; original Zig highlighting is untouched. Prism's MIT license
is retained under `resources/learn/vendor/prism/`.

The upstream snapshot retains the [Zig copyright/license](resources/upstream/LICENSE).
The design and completion criteria are in [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md).
