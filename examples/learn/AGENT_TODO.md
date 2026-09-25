# Learn reference implementation

- [x] Retain failed individual declaration edits as pending source while the
      last working native generation stays callable. Verify the exact
      concatenated-array assertion-first / part-two-second REPL sequence, the
      reverse order, and synchronous/asynchronous compilation. A subsequent
      consistent edit must publish without reloading or reevaluating the failed
      form; this is independent of persistent-state migration. Completed
      2026-09-25: synchronous failures retain requested definitions and pending
      compilation roots; both paths propagate earlier pending constant changes
      to dependent namespaces. Fresh owned nREPL verification: constant reload
      and runtime suites, 30 tests / 208 assertions, zero failures or errors.

- [ ] Investigate native-library TLS-key exhaustion during long-lived macOS
      REPL testing. Owned root REPL (PID 23785) terminated after many repeated
      native generations with dyld: "could not create thread local variables
      pthread key" on 2026-09-25. Preserve generation safety while bounding
      native-library/TLS resources; do not treat this as an assertion failure.
- [ ] Update legacy zig_integration_test.clj syntax: requiring the namespace
      currently fails at line 91 with unresolved `+=`, preventing its broader
      reload/migration tests from running. Targeted constant_reload_test and
      runtime_test remain independently runnable.

- [ ] Fix and expose a reliable coordinated-edit API for live modules with
      comptime invariants. test_arrays must support changing message,
      alt-message and same-message from hello to jello together (and updating
      array-iteration-test's expected character sum) without intermediate
      assertion failures. 2026-09-25 verification found StackOverflowError in
      runtime/source-data-fingerprint at line 889 using register-batch!, both
      whole-file scratch reload and a three-constant live batch. Do not present
      that low-level path as a working recipe yet. Preserve/restore Clojure and
      native publication state on failed batches; add regression coverage.

- [ ] Support anonymous top-level `(k/comptime ...)` blocks so Learn examples
      do not invent named Vars such as matching-initializers/message-length.
      Preserve native comptime semantics, source mapping, reliable reevaluation
      identity, and existing `:attrs #{k/comptime}` use; do not simply drop
      module assertions or make the token unusable as an attribute value.
- [x] Investigate test_arrays message edits failing at matching-string.
      Reproduced changing only hello to jello in an owned REPL: both original
      equality assertions correctly fail at source lines 16 and 26. alt-message
      and same-message still say hello. Restored the REPL constant; no authored
      source edit was made. This mismatch is not a reload defect.

- [x] Finish diagnosis of the complete parallel audit: classify every failed
      and interrupted probe, every namespace-load failure, and every unexecuted
      context across all 290 files. Match expected errors to actual diagnostics,
      identify dependent failures by originating case, and record concrete JVM
      gaps separately from audit artifacts. A completed run is not completed
      classification. Write a reproducible classified report with no missing IDs.
      Finished 2026-09-25: JVM_AUDIT_CLASSIFIED_2026-09-25.md records all 290
      files / 9,588 case IDs. The 1,211 failed/exited probes classify as 524
      JVM interoperability gaps, 509 dependent/repeated failures, 112 expected
      source failures, 55 invalid-context probes, nine state-replay artifacts,
      and two host-safety crashes. These are probe counts, not unique bugs.
      All 866 load/target-blocked cases have reviewed causes; 4,796 remaining
      context-dependent syntax rows are explicitly unexecuted, not passing.

- [ ] Fix the classified generic gaps, prioritizing undefined-error-union
      printing crashing the JVM, builtin result-type adapters, typed-value/
      enum/pointer transport, native control expansion, and comptime reflection.
      Use the classified report's family/file/case IDs as regression inputs;
      remove audit setup/replay artifacts without suppressing genuine errors.

- [x] Complete the all-file parallel JVM audit, not just the six-file sample.
      Run all 290 authored examples through four independent persistent JVM
      workers; checkpoint every case, bound hangs, restart after native panic,
      and retain exact failures plus every unexecuted/context-dependent case.
      Do not equate failed probes with distinct compiler bugs: shared state,
      missing integration setup, and earlier failed adapters can cause cascades.
      Write a new full file-by-file Markdown report and link raw case evidence.
      Completed 2026-09-25: all 290 files, four isolated persistent workers,
      9,588 reviewed cases in 623.4 seconds including two context rechecks.
      2,715 passed; 1,208 failed probes; three worker exits; 5,662 explicitly
      unexecuted/context/load-blocked cases. See JVM_AUDIT_FULL_2026-09-25.md.
      Three defects independently reproduced; this audit did not fix them.

- [ ] Fix and regression-test the independently reproduced JVM array
      destructuring, union-pointer transport and enum-field type preservation
      defects identified in JVM_AUDIT_FULL_2026-09-25.md. Triage remaining
      failures without confusing expected errors or cascades with new defects.

- [ ] Add a source-driven JVM audit of every Learn declaration, including
      private Vars, value realization/printing, native tests, and inner forms
      with their lexical setup. Report every unexecuted or failing case; entry
      point output equivalence is not evidence that individual JVM forms work.
      Start with test-arrays, then audit the complete corpus. Preserve expected
      Zig failures and isolate unsafe/target-specific cases explicitly.
      Use bounded virtual threads for independent source inventory/build work;
      serialize shared native state and output capture. Write a new investigation
      Markdown report with timings, coverage, failures and explicit exclusions.
      Progress: `test/learn/jvm_audit.clj` and `JVM_INVESTIGATION.md` added.
      Full declaration baseline inventories 290 examples; focused arrays now
      pass 119 executed checks with no failures (four explicit context/argument
      exclusions). Values/literals/destructuring add 131 passing checks and one
      threading-context exclusion. Five of eight baseline declaration-inspection failures pass
      after generic fixes; expected upstream errors remain errors. Broader safe
      expression coverage and the remaining comptime-value issues are not done.

- [x] Prefer the `k` alias throughout Learn and the converter, retaining the
      user-owned experimental hello.clj unchanged. Use callable native arithmetic,
      comparison and eager scoped loop forms where Clojure behavior differs.
      Verify the native code/output equivalence after migration; do not claim
      every scoped form or every example independently JVM-safe yet.
      Verified: 98 regression tests / 1,320 assertions; converter 3 / 18;
      292 documentation comparisons, 202 real transcripts, 10 browser checks.
      Remaining known JVM-only issues: explicit comptime-only aggregate
      inspection (std.Options) and native function-table calling conventions.

- [x] Verify inferred numeric constants from an ordinary REPL: Zig radix and
      underscore literals, integers wider than signed 64-bit, and math/inf and
      math/nan (including f128). Do not request storage for comptime-only values
      or emit a missing source-level type after Zig has inferred it.
- [x] Use ak attribute Vars throughout Learn (`ak/export`, `ak/pub`, `ak/enum`,
      `ak/volatile`); update the converter and test quoted/evaluated attributes.
- [x] Make extern implicit in az/defextern; validate extern-dependent native
      bodies without linking before their first invocation. Keep the object
      build/link recipe in float-mode-exe's comment, not at namespace load.
      The recipe prints optimized = 0.001 and strict = 0.0009765625; missing
      symbols fail on invocation, invalid native bodies still fail on definition.
- [x] Regenerate all affected documentation transcripts with ordinary namespace
      loading after the compiler fixes stabilize; retain real declaration-load
      failures instead of bypassing them during output capture.
      Verification: 292 comparisons pass (288 upstream, four reviewed cases),
      202 transcripts retained (176 in-process calls and 26 actual load errors).
      API/emitter/JVM suite: 92 tests / 613 assertions; preparation suite:
      10 tests / 42 assertions, all passing. Converter attribute and native
      reference-requalification regressions pass separately.
      Rebuild in a fresh verification JVM when native examples have already
      mutated globals; retain one JVM for the batch, not one per example.
      Original Zig float-mode files independently tested in Debug and
      ReleaseFast: both return 0.0009765625 in Debug; ReleaseFast's optimized
      function returns 0.001 while strict remains 0.0009765625.

- [x] Preserve native function identity on ordinary JVM function values so
      `(thread/spawn {} testTls [])` and generic higher-order calls accept them;
      verify real thread creation/join without a special REPL wrapper.

- [x] Fix prepared imports for `aguafria.std.builtin.Type` and its nested Union,
      Enum, and EnumField namespaces; verify test-inline-else loads from a fresh JVM.

- [x] Capture native panic frames before the JVM guard jumps, symbolicate them,
      and map generated Zig locations to authored Clojure forms. Preserve the
      original panic and cleanup warning; verify assertions, explicit panics,
      arithmetic traps, and native tests without editor-specific integration.

- [x] Restore omitted-type inference for `az/defvar`; explicit types still
      precede attributes. Remove inserted `:_` placeholders, preserve converter
      inference, and verify native/JVM behavior and the rebuilt reference.
- [x] Fix anonymous container evaluation on the JVM: member declaration names
      such as `:value` must remain native names, not unresolved Clojure symbols.
      Verify static member reads and mutations on the resulting type.
      Both native/JVM paths verified, including inferred booleans and thread-local
      state, anonymous static member mutation, locally bound field types, enums,
      and unions. Declaration/emitter/JVM suites: 88 tests / 595 assertions pass;
      converter inference regression passes. Rebuilt full reference in a fresh
      owned REPL: 292 comparisons passed, 200 in-process transcripts retained.

- [x] Enforce type-first az/defvar across the repository with no legacy parser;
      use set-valued ak keyword attributes for threadlocal/comptime and other
      plain prefixes. Make native thread-local values inspectable/mutable per
      JVM thread; verify thread isolation.
      `:attrs` rejects every non-set value, including singleton flags. Native
      TLS accessors resolve storage on the calling platform thread; JVM/native
      mutation shares that storage without sharing values across threads.

- [x] Add public container `:var` / `:const` member vectors, preserving instance
      `:default` semantics. Migrate all Learn members, and test standalone JVM
      field access and compound mutation against the same native state.
- [x] Remove redundant terminal `ak/return` from Learn functions and methods;
      retain genuine early exits and verify unchanged behavior.
      Fixed implicit returns of error values in error-union/void functions and
      while/else expressions. All 292 outcomes match (288 upstream, four
      reviewed special cases); 200 genuine in-process transcripts regenerated.
      API/JVM checks: 38 tests / 294 assertions; expanded API/emitter/kondo/reload
      checks: 59 tests / 420 assertions, all passing. Learn: 64 tests / 11,367
      assertions, all passing. Full HTML acceptance, snippets, and 10 browser/
      highlighting checks pass. Existing baseline converter failures remain
      recorded separately below; this is not a claim that the entire repo is green.

Goal: the whole Zig 0.16.0 reference, not a selected tutorial. Keep the original
document unchanged, add Aguafria alternatives and real REPL output, and verify
the comparisons. The direct-call/side-by-side follow-up is complete and the
served page has been rebuilt and checked. Compiler gaps are not `ZIG_ONLY`.

- [ ] Show container fields/tags, methods and their documentation when evaluating
      JVM type values and using normal Clojure Var documentation (`doc-comments`).
- [ ] Make `defextern` Vars use the existing native JVM call bridge; verify real
      linked calls and explain unresolved/platform-specific naming examples
      in `identifiers` without calling unsafe demonstration prototypes.

- [x] Use the shared native expression bridge for generated builtins, value
      operators and structural value forms, not per-operator JVM implementations.
      Preserve comptime signature arguments, runtime operand types, native output
      and type-valued results that can be passed back into Zig. Scope-dependent
      syntax still requires an enclosing declaration. Tested values.clj's exact
      boolean print, optional/null comparisons and assertions; unrelated builtins,
      field/index expressions, type round trips and Java callers also covered.
      Qualify the example's equality operators; full values/main ran successfully.
- [x] values.clj: expose ExampleErrorSet as an inspectable type with documented
      error members, without trying to allocate comptime-only `type` storage.
      Explicit az/type aliases use type descriptors rather than lazy value handles.
      JVM, keyword, API and diagnostic checks: 27 tests / 858 assertions passed.

- [ ] Verify the corrected error header and source highlight in the user's
      restarted CIDER session. Do not install editor-specific modes/adapters.
- [x] Fix compilation-error wrapping: throw a standard CompilerException as the
      outer exception, not ExceptionInfo, so Compiler/load cannot reclassify it
      as an execution error at runtime.clj. Preserve the complete native report
      and diagnostic data in the cause; runtime/error-data reads the full chain.
      Regression checks include direct evaluation, load-string, actual native
      compilation failure and successful recovery. CIDER's result popup stays
      at the evaluated expression by editor policy; do not confuse that popup
      with its compiler-source highlight/navigation.
      Verified with a dedicated CIDER 0.50.2 / nREPL 1.3.0 server: a missing-try
      native failure through load-string retains the original .clj:8:3 header
      and full report. The installed Emacs CIDER source parser resolves exactly
      (may-fail) at 8:3. Runtime, diagnostics and API: 37 tests / 242 assertions.

- [x] Teach clj-kondo native `try` semantics inside Aguafria declarations and
      zero-argument JVM calls to process-aware `main`; retain ordinary Clojure
      warnings and normal arity checking. Consolidate OS-specific copies into
      one shared `clj-kondo.exports/io.github.pfeodrippe/aguafria` export.
      Verified hello.clj's two reported findings are gone; five regression
      tests / 35 assertions pass, including importing the shared config and
      hooks from both macOS- and Linux-named JARs. No extra build logic needed.

- [x] Cap side-by-side Zig + Aguafria panels to the viewport width. Keep code
      unwrapped with local horizontal scrolling, fixed tabs and paired heights;
      rebuild and verify responsive layouts and the served page.
      All 10 browser/highlighting tests pass (640–2600px); verified every pair's
      bounds and no-wrap styling, and visually checked the rebuilt page.

- [x] Make primitive type Vars callable, and make `(ak/as value type)` the
      canonical, thread-first-friendly coercion API for primitive and complex
      signature type forms. Return real JVM/native values, preserve Zig checks
      and native ownership, migrate existing calls and binding annotations,
      update converter/tooling, regenerate outputs, rebuild and verify Learn.
      Migrated 822 coercion calls and 1,006 typed example bindings; regenerated
      the 245-file TigerBeetle corpus with the updated converter cache version.
      Verified: 262 library tests / 5,644 assertions; 52 Learn tests / 9,324
      assertions; all 292 upstream comparisons; 10 browser/highlighting tests.
      Dedicated nREPL acceptance covers real JVM scalars, native composites,
      generic-function type preservation, checked ranges and view ownership.
      Rebuilt and visually checked the served code and real `(main)` output.

- [x] Replace redundant named-type map binding annotations with ordinary
      constructor calls throughout the repository, preserving mutability,
      alignment and required coercion annotations. Support locally bound types,
      verify native defaults and Clojure constructor defaults, add regressions,
      rerun upstream comparisons, and rebuild the served Learn reference.
      Audited source, tests, development tooling, resources and all examples:
      migrated all 15 matching bindings in 11 Learn files; no matches remain.
      Verified: 257 library tests / 5,591 assertions; 52 Learn tests / 9,324
      assertions; all 292 upstream comparisons; 10 browser/highlighting tests.
      Native and JVM constructors preserve Zig field defaults, including nested
      and packed structs, and local type aliases respect lexical shadowing.
      Rebuilt and visually checked the served example and its real REPL output.

- [x] Use one explicit member vector for named and anonymous container types.
      Add `az/struct`, `az/enum`, `az/union`, and `az/opaque`; allow enum keywords
      and detailed tag vectors together. Keep methods inside the member vector.
      Reject variadic member syntax, migrate examples/converter/tooling, test
      generic type factories and native calls, and rebuild/verify the reference.
      Verified: 255 library tests / 5,579 assertions; 51 Learn tests / 8,768
      assertions; all 292 upstream comparisons; 10 browser/highlighting tests.
      Direct nREPL calls exercised a generic struct's method and native value
      round-trip. Rebuilt and visually checked the served reference. The simple
      struct converter path and nested declarations also use the member vector;
      the 245-file TigerBeetle corpus was regenerated and its regressions pass.

- [x] Keep the contents tree fully expanded like the original Zig reference;
      use an icon-only, accessible hamburger button for the sidebar.
- [x] Remove the inline ⇄ controls from prose and empty/context-only comment
      recipes. Preserve actual runnable comment forms and all upstream wording.
- [x] Give `az/defextern` and `az/fn-decl` the same return-type-first syntax as
      `az/defn`; migrate all callers, converter output, tests and tooling.
- [x] Support idiomatic `az/defenum` and vector-field `az/defstruct` declarations
      with documentation and nested methods; hand-review and migrate similar
      verbose declarations throughout the Learn examples.
- [x] Map compiler diagnostics to precise Clojure file/line/form excerpts,
      including constant_identifier_cannot_change.clj. Test native and front-end
      errors rather than substituting an example-specific explanation.
- [x] Rerun compiler/Learn tests and source/output verification, regenerate real
      REPL transcripts, rebuild the page, and verify it visually in the browser.

Additional compiler repairs required by the wider regression run:

- [x] TigerBeetle type-factory constant dependencies can fail to stabilize
      (`message-bus-fuzz/MessageBus`). Reproduced with the pre-change compiler
      from HEAD and a freshly converted corpus in `.tmp/learn-api-baseline.LPF18L`.
      Implemented stable cyclic source-graph identities and removed indirect
      self-dependencies. Recursive-group edit/idempotence tests and full corpus
      loading now pass. Full converter regressions: 34 tests / 254 assertions,
      including TigerBeetle compilation, execution and hot reload, all passing.
- [x] TigerBeetle's for/else `unreachable` can be emitted as a quoted identifier.
      The same output is produced by the pre-change compiler. Fixed expression
      translation and added a native regression fixture. The full TigerBeetle
      compile-and-execute regression now passes.
- [x] Preserve C-header documentation after the return type in generated
      function declarations, invalidate cached bindings, and rerun C binding tests.
- [x] Scope the constant-reload test's waits to its provider/consumer modules,
      so an intentionally failed editor compilation cannot poison that test.
      Both pass together, and the complete library suite passes.

- [x] Keep language tabs in their original prose-aligned position while panels
      scroll independently. Place Clojure at the window midpoint. Default to
      side-by-side, support `?view=zig|clj|side-by-side`, and add a collapsible
      contents sidebar with the entire upstream tree expanded. Verify links, keyboard use,
      all paired heights, narrow windows and resizing in the browser.

- [x] Match each Zig/Clojure source block to the taller code block, and likewise
      match Shell/REPL output heights independently (blank space is intentional), including
      individual tabs. In Side by side keep both columns at individual width,
      with Clojure starting at the window midpoint and Zig on its left.
      Keep navigation independently expandable; preserve horizontal access on narrow
      windows (each comparison scrolls independently). Rebuild and check geometry,
      resizing and multiple open pairs.
      Browser regressions cover all 307 pairs and live resizing from 640 to
      2600 pixels; code and output text areas align, tabs stay fixed, and the
      contents toggle and complete tree work. Checked the rebuilt page visually.

Current verification (2026-09-19): all 292 file outcomes pass (161 output,
78 diagnostic, 50 compile-only comparisons and 3 reviewed special cases).
The page records 264 actual comment evaluations across 202 in-process lessons.
49 context-only, 32 deliberately fatal and 7 target-specific cases do not receive
fabricated REPL output. These direct-call recordings replace the older file-runner
transcripts mentioned in the implementation history below.
All 51 Learn tests / 8,768 assertions and ten JavaScript tests pass. The served
HTML matches the built file and recovers the upstream HTML byte-for-byte.
Browser interaction and screenshot checks confirm default side-by-side selection,
307 Side by side controls, paired Shell/REPL panels, highlighting, direct `(main)`
and named-test calls, and the repaired compiler diagnostic. All outcome
fingerprints match the final compiler and verifier.
Generic native-call/compiler regressions also pass: 161 tests / 994 assertions.
The repaired C binding and scoped reload tests pass with the editor suite:
16 tests / 113 assertions. The complete library suite, including the full
TigerBeetle converter/native regressions, passes: 252 tests / 5,539 assertions,
zero failures or errors. clj-kondo's API fixture has zero warnings/errors.

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
- [x] Finish repository-wide API migration validation
      (outside this completed reference; breaking, no compatibility forms):
      `(az/defn foo :i32 [] ...)`, with the name and
      return type beside the macro. No `:-`, no attributes before the return type.
      Same for `az/defn-`. `az/deftest` requires a symbol, defines a normal explicit
      Var and keeps that name on the header line. Its symbol supplies the test
      name; no duplicate `:zig/test-name` metadata or hidden naming fallback.
      Migrate callers, editor hooks, docs and tests; verify old forms are rejected.
      Completed and covered by the final complete library run above.
- [x] Fix labeled `for`/`while` else blocks in the shared converter; native regression.
- [x] Fix switch-prong `comptime unreachable` in the shared emitter: extra
      parentheses incorrectly change Zig's syntax-sensitive error-set checks.
      Native regression plus original reference example pass after the fix.
- [x] Investigate and fix further compiler failures found during verification.
      Added reader @pointer support via the real clojure.core/deref Var, and fixed
      direct comptime let blocks receiving an invalid semicolon, and keyword tuple
      field names (:3) now emit Zig's required quoted identifier. 32 emitter tests /
      146 assertions pass; native pointer/comptime/tuple lessons match upstream output.
      Named-test/public/private API tests: 2 tests / 26 assertions pass. Isolated
      tooling/converter/editor checks: 10 tests / 733 assertions pass; both packaged
      clj-kondo fixtures have zero errors and warnings. The full native library
      suite is now verified after the breaking API migration (results above).
- [x] Fix broader converter-suite failures. The earlier 28-test run reported
      5 failures and 2 errors involving TigerBeetle constant dependencies that do
      not stabilize. Isolated
      baseline/current require-and-await checks both reproduce the same failure.
      The checked baseline used HEAD's converter/emitter, extracted into
      `.tmp/learn-baseline.7zPwlK`; this failure predates the two reference fixes.
      Resolved by stable cyclic source-graph identities and keyword-expression
      translation, with native regressions and the full library run passing.
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
# JVM expression interoperability follow-up

- [ ] Separate baseline converter-suite failures observed on 2026-09-25:
      five fixtures still contain references rejected by current declaration-order
      checks (`Replica`, `Missing`, `left`, `Finished`, `Ready`), reproduced with
      the unchanged HEAD emitter/API/converter; TigerBeetle subprocess tests omit
      the prepared std catalog and its corpus loading also fails. Not caused by
      the method-syntax or output-parallelism changes; do not weaken validation.
- [x] Replace stale flat `a :- type` function metadata with the authored typed
      binding vectors; include return types in ordinary Var docs for public,
      private, generic and external functions without editor-specific code.
- [x] Measure fresh versus cached output regeneration; add bounded virtual-thread
      parallelism for independent native builds without racing JVM namespace
      evaluation or stdout capture. Rebuild and verify the published outputs.
      Fresh four-worker output regeneration: 114 seconds after the final
      compiler changes; warm cache checked without permitting native execution.
- [x] Add public `az/fn` and private `az/fn-` container methods; reuse existing
      named-type constructors instead of redundant `az/init` forms throughout
      applicable Learn lessons, preserving documentation and native semantics.
- [x] Restore missing REPL output after the 2026-09-25 server restart. Do not
      rebuild an existing HTML snapshot merely to serve it; regenerate actual
      comment-form transcripts and verify the served Hello World output.
      All 292 reference cases verified; 200 in-process transcripts restored.
      Served Hello World inspected visually; all 10 browser/highlighting checks pass.
- [x] Reuse unchanged translations and outcomes; invalidate edited lessons and
      transitive local dependencies, damaged artifacts, failures, and common
      compiler/verifier changes. Test a warm build without native execution.
      62 Learn tests / 11,356 assertions pass. Full warm translation/verification
      completes in about 8 seconds with native execution paths forced to throw;
      the retry pass reran only four failed stateful cases in a fresh JVM.
- [x] Remove the committed std metadata EDN. Generate it and namespace stubs
      under ignored `generated/` through `:prepare`; verify clean-cache and
      cached preparation, direct imports, docs, and the rebuilt Learn reference.
- [x] Retain inferred alias definitions in generated docs; qualify field types
      such as `items: aguafria.std.ArrayList/Slice`; show a type's available
      fields in the constructor/type documentation.
- [x] Share alias and built-in field discovery between std and package prep,
      including nested generic slice types, aliases of containers, and cyclic
      alias rejection. Test actual JVM/native access and prepared editor metadata.
- [x] Audit all Learn field accesses and migrate known named types to their
      namespace getters. Retain `az/field` for local/anonymous types, dynamic
      member names and bound methods. Rebuild and compare the full reference.

Verified 2026-09-20: migrated 26 examples without changing native field access.
The JVM/native suites pass 47 tests / 6,217 assertions; emitter tests pass
42 / 243 and Learn tests pass 58 / 11,342. Rebuilt all 292 file examples:
288 upstream outcomes match and four reviewed special cases pass, including
200 in-process comment-form REPL transcripts. Fixed lexical arguments shadowing
namespace aliases rather than adding a spurious generated module dependency.
Clean std catalog generation and repeated `clojure -X:prepare` work from both
the repository and Learn; cache reuse and regeneration after corruption were
checked. Metadata now lives at ignored `generated/aguafria/zig-std.edn`;
the former tracked catalog was removed and is reproducible from pinned Zig.
All 10 browser/layout/highlighting tests pass (640–2600 px); the served page
also passed the named-getter, real REPL output, and mapped leak-location checks.

- [x] Audit declaration order in every Learn example and snippet. Move helpers
      and constants before their consumers without renaming them; add an audit
      regression and rebuild/verify the page (`test_container_level_variables`
      must define `add`, then `x`, then `y`, then its test).

- [x] Expose native fields as normal completable namespace Vars such as
      `aguafria.std.ArrayList/-items` and `-capacity`, using shared catalog
      extraction and field dispatch. Verify getters from JVM and compiled forms,
      real argument/docs metadata, and another native container.

- [x] Reject unknown references at declaration registration, including source-only
      evaluation and programmatic batches. Dependencies must already exist;
      a catalog of later source declarations is not permission to use them.
- [x] Keep incomplete snippets as non-runnable syntax excerpts. Remove the added
      `declare` forms; render their syntax without registering or evaluating
      declarations. Do not invent missing dependencies or entry points.
- Current reference verification: 288 upstream outcomes matched plus 4 reviewed
  special cases; 200 in-process REPL transcripts. All 58 Learn tests / 11,291
  assertions and 10 browser tests passed. Rebuilt the full HTML and checked the
  served page for dependency ordering and absence of added `declare` forms.
  Compiler/JVM/field-accessor regressions: 53 tests / 6,229 assertions, zero
  failures or errors (including ordinary, source-only, and batch registration).

- [x] Inspect generated/native container fields instead of displaying unlabelled
      storage bytes. Print every ZigValue with `#aguafria.zig.value.ZigValue[...]`
      consistently in ordinary and pretty printing; retain plain decoded deref.
      Verify mutable ArrayList contents, nested values and safe pointer display.
- [x] Reproduce direct allocator calls versus `detect-leak-test`: distinguish
      per-call success from test-scope leak checking. The exact native test fails
      for the intended leak; adding deinit passes. Direct calls pass and can be
      cleaned up afterward. Neither path reproduced the separate count exception.
- [ ] Resolve the separately reported `count not supported on this type: ZigValue`
      exception once its full JVM stack trace/evaluated form is available; asked
      the user for it. Do not mislabel the expected leak as this exception.
- [x] Map native runtime/leak stack frames to original Clojure source locations
      and forms using shared source markers. Preserve the full native trace and
      expose mapped locations in exception data, without editor-specific code.
      Verified the real Learn leak maps to the append form (now line 12 after
      the owner's ArrayList alias edit). Raw native stderr remains in :native-stderr;
      :stderr includes the preserved trace and mapped source report.
- Latest printer/interop/diagnostic regression: 112 tests / 669 assertions,
  plus 57 Learn tests / 10,802 assertions, zero failures or errors.
  Full reference acceptance passed: 289 matching upstream outcomes plus 3
  reviewed special cases, 202 in-process REPL transcripts. Browser checks 10/10;
  live served-page check confirms the mapped Clojure line/form and single leak
  report. Visually inspected the mapped report. Preserved and reverified the
  owner's concurrent `al/append` edit; regenerated the complete HTML.

- [x] Fix JVM construction of void-payload error unions, including
      `(ak/as (az/error-value :DemoError) [:error-union :anyerror :void])`.
      Shared coercion adapters return the typed value explicitly; canonical
      type resolution covers shorthand, finite/anyerror sets and mutable writes.

- [x] Expose compiler-provided `builtin` through the regular require/import
      infrastructure (distinct from `std.builtin`), and use `builtin/is_test`
      in `testing_detect_test.clj` instead of an ad-hoc `ak/import` constant.
      Converter and all authored file imports use the namespace. Tests cover
      actual JVM values and different ordinary-function/native-test contexts.

- [x] Follow-up found while checking ArrayList: direct JVM bound-method access
      such as `(az/field list :append)` needs native callable transport; field
      reads and passing the list to a compiled function already work.
      Reproduce the complete user expression with `testing/allocator`, not only
      a page-allocator substitute. Preserve native receiver mutation and test
      allocator context across separate JVM calls.
      Verified repeated append, length/contents, cleanup with the same native
      test allocator, and mutation of an unrelated hand-written Counter type.

- [x] Expose generic-container methods as ordinary required namespace Vars
      (e.g. `aguafria.std.ArrayList/append`) with argument/doc metadata for editor
      completion. Use the prepared hidden import catalog and shared native
      dispatch, not editor-specific completion code or an ArrayList-only shim.
      Prepared 1,681 hidden std namespace entry points with static declarations;
      the standard nREPL completions operation returns `array-list/append` and
      related methods. Native and JVM method-Var calls pass regression tests.

- Latest regression run: 120 native/JVM/catalog/preparation tests, 4,044
  assertions; 28 converter tests, 210 assertions; 57 Learn tests, 10,801
  assertions. All passed. Full post-change reference acceptance passed:
  289 matching upstream outcomes, 3 reviewed special cases, 202 in-process
  REPL transcripts, 356 sections and 307 tabbed figures. Browser checks passed
  10/10, including every code/output pair across live resizing; removed upward
  pixel rounding that could add one extra pixel to measured code heights.
  The served-page check confirmed real test output, builtin imports, qualified
  equality and a single leak diagnostic. Visually inspected the rebuilt page.

- [x] Print native test diagnostics once: keep the full stdout/stderr in
      exception data, but do not repeat already-printed output in its message.

- [x] Make inferred member literals such as `:.empty` construct mutable typed
      values through the JVM bridge, including `(ak/var :.empty (std/ArrayList
      :u21))`; cover generic containers and other inferred-member defaults.

- [x] Qualify equality and inequality throughout authored examples, snippets,
      and inline sources with `ak/==` / `ak/!=`; fix converter output and add
      regression coverage so bare JVM-incompatible calls do not return.

- [x] Check `az/deftest` at definition time against declarations already present,
      without executing it; failed definitions/redefinitions must not publish.
      Move helpers before tests in Learn, starting with `testing_introduction.clj`.

- [x] Add `az/zig-source!`: print formatted declaration/type/value Zig to `*out*`
      using the pinned formatter, without invoking inspected functions. Verify
      Vars, native globals, documented structs, tests, imported references,
      primitive/compound type schemas and typed native arrays.

- [x] Express mixed destructuring with ordinary `let`: destructure first, then
      rebind selected locals with `ak/var`. Preserve Clojure's shadowing semantics
      through hygienic native local names. Support `:_` assignment discards.
      Direct JVM and compiled tests cover scalars, a native struct, a typed error,
      and a native slice; mutable copies retain the defining module's type identity.
      Zig 0.16.0 ReleaseFast/LLVM comparison of `destructuring_mixed` produced
      identical optimized main instructions (only anonymous labels differ).
      Keep declarations lexical: assignment must not introduce invisible locals.

- [x] Support typed `ak/undefined` mutable storage and destructuring assignment
      from ordinary JVM code; never require reading undefined values first.
- [x] Use `:_` for inferred array lengths. Make `az/init` and `az/array-init`
      value-first, migrate callers/converter/tests, and verify both JVM and Zig.

- [x] Preserve original Zig identifiers rather than renaming types/functions for
      exposition; audit union examples and other authored lessons for gratuitous
      helper functions and renames. `change_active_union_field`: `Foo`, `bar`, `f`.

- [x] Replace Learn mutable/type binding metadata with executable constructors;
      migrate all assignment forms to `ak/=` and verify native semantics.
- [x] Keep a visible boundary between captured native output without a trailing
      newline and the printed REPL result (notably `mutable_var.clj`: `5679`, `nil`).

- [x] Reject invalid concrete private function bodies when their own definition
      is evaluated, not only when a later caller forces Zig's lazy analysis.
      Preserve private visibility, source-only tooling and generic specialization.
- [x] Preserve typed error-set values through field access, coercion, mutable
      initialization and reassignment from ordinary JVM code.

- [x] Preserve characters and string-literal results across direct JVM calls;
      verify nested `mem/eql` and `debug/print` using actual native execution.
- [x] Provide explicit mutable native values for ordinary Clojure `let` and
      assignment; test optional/slice ownership, scalar mutation, and equivalent
      compiled Aguafria syntax. Binding metadata alone cannot change Clojure locals.
- [x] Prevent Zig assertion/safety panics at the JVM call boundary from aborting
      the JVM. Test failures in a disposable JVM before the live development REPL;
      document the limits for arbitrary memory corruption, exit and custom panic.
- [x] Refresh authored lesson copies (including current user edits), regenerate
      complete HTML and actual REPL evidence, compare original Zig outcomes, and
      check the served page rather than only the generated files.
      Final clean run: 289 upstream outcomes + 3 reviewed special cases passed;
      acceptance gate passed with 202 in-process REPL transcripts. Learn tests:
      55 tests / 10,212 assertions. Browser checks: 10/10, plus live served-page
      verification of mixed destructuring, `5679`/`nil` separation and viewport fit.
      `hello.clj` was restored by its owner; no user edits were moved or replaced.
