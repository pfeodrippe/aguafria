# Learn Aguafria Zig

The complete [Zig 0.16.0 language reference](https://ziglang.org/documentation/0.16.0/),
with its original HTML, sections and output preserved and per-example Zig/Aguafria
tabs. Applicable file examples, contextual blocks and inline equivalents are
hand-written and checked against the pinned reference.
This is not a claim of complete language compatibility.
See [AGENT_TODO.md](AGENT_TODO.md).

The published reference is not rewritten: prose, Zig code, Shell commands and
output stay verbatim. Only Aguafria alternatives, their recorded REPL output and
language-switching controls are added. Removing these additions must recover the
complete original HTML byte-for-byte; a regression test enforces this.

From this directory:

```sh
clojure -M:translate       # regenerate displayed namespaces and emitted Zig
clojure -M:blocks          # handwritten blocks; check syntax and shared native fixtures
clojure -M:inlines         # check short forms; compare closed expression outputs
clojure -M:outcomes        # compare original + Aguafria outcomes; fail on mismatch
clojure -M:build           # offline build/site/index.html, inventory and coverage
clojure -M:serve           # http://127.0.0.1:8096/
clojure -M:test            # snapshot/build/evaluation regressions
node --test test/highlight_test.cjs # exact-text Clojure highlighting regressions
clojure -M:verify          # fail for missing, stale or mismatching evidence
clojure -M:dev:nrepl       # dedicated local development REPL
```

The example uses the library checkout so compiler fixes can be tested immediately.
All native builds use **Aguafria’s embedded Zig**, never a `zig` found on PATH.
No other example is a dependency. Normal builds work from the hash-locked,
MIT-licensed snapshot and do not download the reference again.

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
panel records this real evaluation's output, result or exception; it never copies
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
