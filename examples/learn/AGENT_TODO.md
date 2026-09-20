# Learn reference implementation

Goal: the whole Zig 0.16.0 reference, not a selected tutorial. Keep the original
document unchanged, add Aguafria alternatives and real REPL output, and verify
the comparisons. The direct-call/side-by-side follow-up is complete and the
served page has been rebuilt and checked. Compiler gaps are not `ZIG_ONLY`.

Current verification (2026-09-19): all 292 file outcomes pass (161 output,
78 diagnostic, 50 compile-only comparisons and 3 reviewed special cases).
The page records 264 actual comment evaluations across 202 in-process lessons.
49 context-only, 32 deliberately fatal and 7 target-specific cases do not receive
fabricated REPL output. These direct-call recordings replace the older file-runner
transcripts mentioned in the implementation history below.
All 50 Learn tests / 8,763 assertions and six JavaScript tests pass. The served
HTML matches the built file and recovers the upstream HTML byte-for-byte.
Browser interaction and screenshot checks confirm default Aguafria selection,
307 Side by side controls, paired Shell/REPL panels, highlighting, direct `(main)`
and named-test calls, and the repaired compiler diagnostic. All outcome
fingerprints match the final compiler and verifier.

- [x] Recognize public `:!void` main with `std.process.Init` / `Init.Minimal`
      for `(main)` and `(main ["arg"])`, initializing and calling it in the JVM.
      Keep ordinary function arity and C-exported entry arguments explicit.
      Live tests cover full Init output, Minimal argv, and ordinary arity.
      Minimal startup no longer initializes unused full-process resources.
      Focused native-call suite: 21 tests / 108 assertions passing, plus six
      actual Learn comment recipes including the process-init Hello World.
      Compiler/runtime/API regressions: 64 tests / 347 assertions passing.

- [x] Add a per-example Side by side tab, keeping Zig/Shell and Aguafria/REPL
      together, with keyboard controls and a usable narrow-screen layout.
- [x] End every authored example with a direct in-process `comment` recipe:
      call its own main/test/function Var, never a reference/file runner.
      Compile-only and incomplete illustrations must not pretend to be runnable.
      Record exact evaluations, including genuine failures, and rebuild the page.
- [x] Make named test calls execute native code inside the JVM via Panama;
      launching a Zig test executable is not a direct in-process call.
- [x] Fix native hot-reload inline constant folding so `inline_call.clj`'s
      direct `(main)` retains the original Zig compile-time behavior.
- [x] Resolve the remaining direct-comment verification failures (top-level
      @This result transport, enum arguments, quoted exports, external float-mode
      linking, and intentional compile-time errors); rerun the complete sweep
      against the final compiler fingerprint before rebuilding the served page.
- [x] Preserve native diagnostics wrapped by Clojure's evaluator, while still
      rejecting JVM arity/resolution errors as failed recipes. Check both the
      wrapped-error path and the real default branch-quota test call.

- [x] Move std entry points from `src/` to ignored `generated/`, using the existing
      catalogs and standard source-dependency prep. Name aliases `:prepare`.
      Extend package prep to generate normal `aguafria.pkg.*` entry points,
      refreshing added/changed/removed dependencies without a bootstrap require.
      Include std entry points in packaged JARs; verify fresh REPLs and rebuild Learn.
      Verified standard source-dependency prep in an isolated consumer, direct
      std imports from a code JAR, and direct third-party UUID imports/calls.
      Generated source entry points were moved to a recoverable `.tmp` backup.
- [x] Make ordinary JVM calls execute native Zig for imported function Vars and
      comptime/generic functions, rather than returning form data or attempting
      to marshal `type` across the C ABI. Verify the user's `maximum :bool`
      example, `debug/print`, actual return values, error propagation and edits.
      Route synchronous native output to bound Clojure writers, restoring process
      streams on failure. Name the bridge `aguafria.zig.jvm`: no nREPL middleware
      or development-only restriction. A plain Java program verifies native
      boolean/numeric results and printing; six bridge tests / 26 assertions pass,
      including adapter reuse, native state and hot reload.
      Combined library regression suite: 87 tests / 3,789 assertions, no failures.
      Rebuilt 290 real REPL transcripts; all 292 file outcomes, 41 Learn tests /
      7,899 assertions and six JavaScript tests pass. Visually checked the served
      page with Aguafria selected, highlighting and native output.

- [x] Make nested std namespaces directly requireable from a clean REPL,
      without `aguafria.std` bootstrap imports or reference-runner setup.
      Generate classpath entry points from the existing std catalog.
      Support `:!void` / `:!T` and `[:! payload]` returns, remove redundant qualifier maps in the
      lessons, and verify native calls, error propagation and regenerated output.
      Verified fresh JVM imports, native calls and hot reload; 73 library tests
      / 3,719 assertions and 41 Learn tests / 7,899 assertions pass. Rebuilt all
      290 REPL transcripts and checked all 292 file outcomes. Browser inspection
      confirms syntax highlighting, `:!void`, real output and independent tabs.
      The prepared entry points above supersede the initial std-only solution;
      both std and third-party packages now support ordinary direct requires.

- [x] Flatten the 290 authored files into `resources/learn/example/` with
      `learn.example.*` namespaces and put the 15 explanatory blocks under
      `resources/learn/snippet/` with `learn.snippet.*` namespaces. Match original
      Zig-derived filenames, replacing spaces, dots and hyphens with underscores
      for ordinary Clojure resource lookup; use hyphens in namespace symbols.
      Remove conversion-provenance docstrings,
      retain real documentation, update references/captions and verify outputs.
      Fresh verification: 40 tests / 7,385 assertions, all 292 file outcomes,
      snippet/inline checks and six JavaScript tests pass. Every namespace maps
      to its actual resource path; rebuilt and checked the served captions.

- [x] Default interactive example tabs to Aguafria Zig. Keep explicit Zig links,
      independent switching and keyboard navigation working; preserve the original
      Zig fallback without JavaScript. Six JavaScript tests and strict `verify!`
      pass; rebuilt the served reference. Isolated browser DOM checks confirm
      307 Aguafria selections by default and 306 plus one explicit Zig selection.

- [x] Show each Aguafria example's actual Clojure filename in a caption matching
      the Zig tab. Keep original namespace documentation and the Zig HTML intact.
      Conversion-provenance docstrings are now removed: the matching filenames
      identify the source without repeating it in the code.

- [x] Generic differential verification uses each original Zig example as the
      oracle: compare output, test results or semantic diagnostics without
      hand-written expected answers per case. Equal process exit codes alone
      cannot pass an output mismatch. Save reports for the entire batch, then
      throw on any failure so `clojure -M:outcomes` fails in CI as well.
      Keep compilation-only and reviewed language-specific cases distinct from
      runtime comparisons. Pointer allocation addresses have one narrow reviewed
      exception; do not normalize arbitrary changing numbers out of output.
- [x] Make each `az/deftest` Var callable at the REPL using embedded Zig. Verified
      normal require + `(integer-pointer-conversion-test)` without registration
      bindings: one native test passed. Five focused tests / 46 assertions cover
      sibling/import selection, native-only types, redefinition and failures.
      Verifier cleanup now also removes its temporary loaded-library marker,
      so ordinary `require` works after the namespace was inspected.
- [x] Remove per-example progress badges and generated boilerplate comments;
      use readable, unhashed lesson namespaces. Keep verification evidence in the
      coverage report, not repeated between code examples. Rebuild and inspect
      the actual served page, including the comptime example the user identified.
- [x] Remove redundant `:explicit-return` attributes in lessons and converter
      output. Fix default handling of `!void` and `noreturn`; early returns and
      intentional errors preserved. Emitter/destructuring: 49 tests / 231
      assertions passed; converter cleanup: 4 tests / 27 assertions passed.
- [x] Accept terminal `(ak/unreachable)` in non-void functions and nested tail
      branches without a return attribute. Focused emitter suite: 37 tests /
      173 assertions pass; the handwritten inline-else lesson passes embedded Zig.
- [x] Match diagnostic headers rather than arbitrary `error:` substrings in
      stack-trace source excerpts. The runtime error-set cast lesson exposed
      `const casted_error: Set2` being mistaken for another compiler error.
      Regression checks also retain actual panic and test-runner failure lines.

- [x] Write implementation plan before project implementation.
- [x] Clone clean official 0.16.0 release under repo `.tmp`, pin peeled commit.
- [x] Snapshot the original rendered HTML, template, 292 examples, renderer,
      doctest harness and MIT notice with SHA-256 lock.
- [x] Inventory 356 heading anchors, 292 file references and 1,402 syntax/tool snippets.
- [x] Preserve all original headings/figures/output in the built HTML; tested.
- [x] Independent accessible tabs, keyboard navigation, copy control, offline assets.
- [x] Dedicated nREPL; evaluate exact displayed Clojure, not hidden converter forms.
- [x] First pass: 289 structurally converted file examples; no raw-source escape.
- [x] Review two invalid Zig doc-comment cases as genuinely `ZIG_ONLY`.
- [x] Translate the missing-initializer example into an intentional Aguafria
      binding error and verify its specific diagnostic.
- [x] Add readable Hello World overrides using normal std Vars and defaults.
- [x] Build pinned upstream doctest harness using embedded Zig.
- [x] Fix test module-tree preparation: materialize imports before root compilation.
- [x] Finish original/emitted native outcome sweep and targeted corrected reruns:
      289 upstream-outcome passes, plus 3 reviewed special-case passes (two
      invalid Zig comment cases and one intentional Aguafria binding error).
- [x] Compare actual executable/test output and semantic error/panic messages,
      beyond upstream exit checks. Fresh sweep: 161 output matches, 78 diagnostic
      matches, 50 compile-only matches, and 3 reviewed special-case passes.
      Only the allocation example permits differing nonzero pointer addresses;
      executable output is separated from verbose compiler cache traces.
- [x] Bind native evidence to the displayed Clojure, emitted Zig, pinned source,
      library, embedded compiler, harness, verifier and reviewed exceptions.
      Reevaluate displayed Clojure before compilation; reject stale evidence.
- [x] Add a normal REPL `learn.reference/run-example!` entry point. Capture its
      actual evaluation, native output, return value or exception. Render REPL
      beneath Aguafria and keep the original Shell beneath Zig, switched together
      with the source; never copy Zig's output to impersonate a REPL run.
      Runnable, compile-only and front-end-error cases have native regressions.
- [x] Finish fresh file-corpus capture/verification of the REPL transcripts and
      visually check paired language/output panels. All 290 Aguafria file panels
      have genuine REPL transcripts; the other two files are Zig-only cases.
- [x] Compare `@compileLog` payload values and types exactly, not just its
      intentional compile-error diagnostic; changed/missing values fail regression
      checks. Diagnostic-only comparisons exclude stack addresses/source locations
      and therefore are not a claim of identical stack traces or exhaustive semantics.
- [x] Translate all 15 larger Zig blocks with exact displayed-source evaluation
      and embedded Zig AST round-trip parsing. Distinguish shared native fixtures
      from illustrations that have no standalone upstream program or output.
- [x] Hand-write all 15 larger block sources; replace their mechanical drafts.
      Eight shared native fixtures now compare original/emitted output: optional
      error handling, allocation/cleanup event order, specialized function bodies,
      and the intentionally non-atomic compare-exchange illustration. All eight
      comparisons pass. These fixtures do not prove real allocator behavior or
      atomic memory ordering; unsupported context remains syntax-only.
- [x] Review the six remaining contextual illustrations (449, 453, 459, 667,
      1214, 1287). Their omitted definitions, external headers, compiler-internal
      details or unfinished bodies are intentional in the original document.
      Keep handwritten syntax counterparts and record specific reasons in
      fragment-overrides.edn. Do not invent runnable programs or outputs for them.
      The user clarified that completion means the examples and matching real
      outputs, not expanding these illustrations into additional projects.
- [x] Extend generic comparisons to all 351 hand-written short forms, covering 470
      inline occurrences. All 251 code mappings emit and parse; 77 distinct closed
      expressions run on both sides with identical output (86 occurrences).
      References resolve to actual Vars; syntax notes remain prose, not fake code.
      Alternative operator forms are not misrepresented as sequential programs.
      Source/compiler-bound reports distinguish execution from syntax checks.
      The same `clojure -M:inlines` command passes in a fresh JVM, including
      bootstrap of nested std namespaces before resolving their Vars.
      Fix enum-literal spelling to :.tag rather than a bare identifier; regress
      every authored standalone enum literal. Module-level snippets evaluate real
      declaration macros in isolated, cleaned-up namespaces with stable evidence.
      One upstream pointer-alignment assertion fails in both original/emitted code:
      explicitly check its exact diagnostic, not merely matching failure exits.
- [x] Add the missing saturating left-shift assignment (`ak/<<|=`) to the compiler
      catalog, generator, converter and emitter. Leave incomplete non-void bodies
      incomplete so native return-path diagnostics stay available without flags.
      Focused emitter suite: 39 tests / 184 assertions pass; keyword/converter
      regression checks pass. The subsequent full file-corpus recheck passes:
      161 output matches, 78 diagnostic matches, 50 compilation-only matches and
      3 reviewed special cases, with all 290 real REPL transcripts regenerated.
- [x] Verify filter-independent test discovery for block 1181 explicitly: the
      Aguafria test-mode comptime import replaces an unnamed Zig test, so the
      empty test count differs. Compare meaningful imported test identities and
      filter behavior, not falsely identical full runner output. All 13 scenarios
      pass: native build/filter/import probes and Windows cross-compilation.
      Windows checks are compilation-only, not execution on Windows. Preserve
      runner counts and actual payloads; altered output or diagnostics must fail.
- [x] Preserve the other 9 larger C/JavaScript/PEG context blocks unchanged.
      Explanations stay in verification metadata, not in the original prose.
- [x] Translate/review all 1,362 inline syntax occurrences.
      Progress: all 1,362 occurrences pair in order with the published HTML;
      892 type/keyword/builtin/context-name references have individually toggleable
      equivalents with real catalog-Var or emitter checks. These explicitly are
      reference mappings, not standalone execution tests. Another 470 occurrences
      have handwritten translations or explicit syntax explanations; none are pending.
      `learn.inline` owns this cohesive short-snippet responsibility.
      The published HTML says `std.debug.dumpStackTrace` where the pinned template
      says `std.debug.dumpErrorReturnTrace`; preserve both and report the discrepancy,
      never silently relabel the published code. Coverage records the reviewed pair.
- [x] Preserve all 16 shell-command snippets without annotations. User clarified
      that additions are limited to Aguafria examples, recorded REPL output and
      switching controls. Do not alter Shell labels, commands, or output. Keep
      tooling-only explanations in internal inventory metadata.
- [x] Enforce whole-document preservation, not just heading/figure equality:
      remove only our added UI and compare the entire HTML with the pinned
      published source. Remove work-in-progress banners and contextual labels
      outside the Aguafria alternatives. Test mutations of prose and Shell output.
      Every build now enforces exact reconstruction of the original HTML.
      The live published HTML and pinned snapshot both hash to
      81374d86d2afee53cf42e52c970ce807d8960beb7725744c2ea65f8479dac457.
- [x] Proper Clojure syntax highlighting for Aguafria examples and short snippets.
      Pin and bundle Prism's core/Clojure grammar locally, preserve its MIT license,
      and leave original Zig markup untouched. Token spans use text nodes so Copy
      preserves the source text, including Unicode/NBSP, rather than
      interpreting source as HTML. Cover metadata, character literals, malformed
      examples, exact-text round-trips for every authored source, and browser QA.
- [x] Remove the redundant handwritten/success labels from verified examples.
      Keep draft and syntax-only limitations in the coverage report.
- [x] Use the test symbol itself as its name; remove `:zig/test-name` from all
      handwritten lessons and converter output. Reject the removed option rather
      than adding a compatibility path. Native comparison maps only exact
      AST-derived runner labels; real REPL transcripts stay unmodified.
- [x] Verify the functions/testing, types, runtime/comptime error and destructuring
      batches against their originals.
      Add fixed vector `let` bindings and vector `set!` for the destructuring
      lessons, with focused statement/expression/native regressions. Four lesson
      authoring errors were fixed; all 289 non-special files translate again.
      Native verification also caught missing quoting of Zig-reserved Clojure
      names (`error`, `:enum`, `:fn`) and a statement used as a switch-prong value.
      Fix reserved names centrally from the bundled keyword catalog; use the
      proper comptime expression in the lesson. Recheck all authored outcomes.
- [x] Hand-write all 290 applicable file examples, retaining teaching comments
      and using normal `let` bindings and meaningful local names. The two remaining
      files intentionally demonstrate invalid Zig documentation comments.
      Converter output is only a verification/reference aid, not the finished
      lesson. All new batches and the final integrated corpus pass the pinned
      native harness and generic output/diagnostic comparisons.
      Regression checks reject hash-named bindings, ak/const or ak/var local forms,
      split declaration names, and string-named tests in authored sources.
- [ ] Separate library follow-up: finish repository-wide API migration validation
      (outside this completed reference; breaking, no compatibility forms):
      `(az/defn foo :i32 [] ...)`, with the name and
      return type beside the macro. No `:-`, no attributes before the return type.
      Same for `az/defn-`. `az/deftest` requires a symbol, defines a normal explicit
      Var and keeps that name on the header line. Its symbol supplies the test
      name; no duplicate `:zig/test-name` metadata or hidden naming fallback.
      Migrate callers, editor hooks, docs and tests; verify old forms are rejected.
- [x] Fix labeled `for`/`while` else blocks in the shared converter; native regression.
- [x] Fix switch-prong `comptime unreachable` in the shared emitter: extra
      parentheses incorrectly change Zig's syntax-sensitive error-set checks.
      Native regression plus original reference example pass after the fix.
- [ ] Separate library follow-up: investigate any further compiler failures.
      Added reader @pointer support via the real clojure.core/deref Var, and fixed
      direct comptime let blocks receiving an invalid semicolon, and keyword tuple
      field names (:3) now emit Zig's required quoted identifier. 32 emitter tests /
      146 assertions pass; native pointer/comptime/tuple lessons match upstream output.
      Named-test/public/private API tests: 2 tests / 26 assertions pass. Isolated
      tooling/converter/editor checks: 10 tests / 733 assertions pass; both packaged
      clj-kondo fixtures have zero errors and warnings. Full native library suite
      is not yet verified after the breaking API migration.
- [ ] Separate library follow-up: broader converter-suite failures. The full 28-test run reported
      5 failures and 2 errors involving TigerBeetle constant dependencies that do
      not stabilize. Do not claim the overall library suite is green. Isolated
      baseline/current require-and-await checks both reproduce the same failure.
      The checked baseline used HEAD's converter/emitter, extracted into
      `.tmp/learn-baseline.7zPwlK`; this failure predates the two reference fixes.
- [x] Complete browser light/dark, responsive, copy, deep-link and no-JS QA.
      Already observed: original dark layout, mouse switching, ArrowLeft/End,
      and independent first/second example selection work. Copy is now verified
      against the real macOS clipboard: exact 397-character Hello World match.
      The new explanatory-block tab and its syntax-only label were also inspected
      in the live browser. The subsequent handwritten pass replaced that earlier
      generated formatting; no syntax-status labels remain in the final page.
      Inline toggles now pass mouse, Enter and independent-selection checks;
      all IDs are unique and no button is nested inside a link. A temporary
      scriptless preview was visually checked: original content remains visible,
      translated alternatives/controls stay hidden. The temporary route was removed.
      Handwritten pointer-arithmetic source and its genuine two-test REPL result
      have now also been inspected in the live browser, with ordinary let bindings
      and no generated names. Original Shell remains on the original Zig tab.
      Narrow-viewport DOM bounds fit, but the automation's viewport override
      produced a blank screenshot despite visible DOM. Reset restored rendering;
      do not claim narrow visual QA passed. Light/responsive visual checks remain.
      Initial isolated Chrome screenshot commands also captured blank images;
      those attempts were not passing evidence. Subsequent controlled Chromium
      capture through CDP produced real inspected screenshots: 1440px light and
      480px dark, with actual mouse/Enter interactions and independent selection.
      Fix the inline wrapper's lost pre/code scrolling: before, page widths grew
      to 1498/1058px; now they are exactly 1440/480px. Trackpad-style horizontal
      scrolling moves only the long signature (250px), not the page. A fresh
      script-disabled screenshot retains the original document with controls and
      translated alternatives hidden. Evidence and the runnable capture script:
      `.tmp/learn-browser.muIEoD/{results.json,check.mjs,*.png}` at repo root.
      Direct Aguafria-panel links select and reveal their panel; JavaScript
      regressions cover independent selection and malformed hashes.
- [x] Replace remaining acceptance-stage placeholders with full coverage/evidence
      checks. Missing/stale/mismatching file, block and inline results must fail;
      require recorded REPL output and reviewed context-only illustrations.
      Twelve negative regression cases verify that incomplete evidence fails.
- [x] Final full regeneration/comparison, acceptance and scope-limited diff review.
      `translate!`, `verify-outcomes!`, `translate-blocks!`, `verify-inlines!`, and
      `verify!` pass in the dedicated learn REPL. A fresh JVM's `clojure -M:verify`
      exits 0. All 292 file dispositions pass, with 290 real REPL panels;
      15 handwritten blocks and all 1,362 inline occurrences are accounted for.
      Final browser rerun passes at 1440px/light and 480px/dark, including mouse,
      keyboard, independent snippet switches and no-JavaScript original content.
      Both rendered layouts contain 290 REPL panels and zero editorial labels;
      screenshots inspected. `git diff --check` passes. No commits or staging.

File authorship is complete: 290 handwritten sources, two reviewed `ZIG_ONLY`
cases, no unregistered drafts. All 121 newly authored lessons passed their batch
checks, including the last four fixes (unreachable form syntax and explicit
type-position array casts). The final all-292 fresh-evidence sweep passes:
161 output matches, 78 diagnostic matches, 50 compilation-only matches, and
three reviewed special cases. The rebuilt site has 290 genuine REPL panels.

Learn regressions: 39 tests / 5,856 assertions pass via the dedicated nREPL,
including ordinary require + native test invocation after verifier cleanup.
The current emitter suite passes 39 tests / 184 assertions; the earlier focused
callable tests passed 5 / 46, canonical API 2 / 30, converter cleanup 4 / 27.
Highlighting/deep links: five Node tests pass with exact character round-trips for
all 290 authored lessons and 15 larger fragments. Browser screenshots verify the
reported comptime case has a
readable namespace, ordinary let, no return attribute or boilerplate, and its
real expected compile-time error in the REPL panel. The current comptime lesson
also passes keyboard source/output switching and exact macOS clipboard copying.
No per-example status labels remain; evidence stays in coverage reports.

Nothing remains for the requested reference deliverable. Broader library
migration/test follow-ups listed above are separate work, not extra features or
expanded acceptance for this reference. Stop here as requested.
All 15 larger blocks are handwritten; eight have native output
comparisons and one has explicit test-discovery verification. File-example success
does not establish coverage of all Zig.
