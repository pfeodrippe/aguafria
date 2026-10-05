# Learn reference implementation

## Zig 0.17.0 migration — October 3 (in progress)

- Accepted storage/proof snapshot: 2434/2741, 307 incomplete, 353.884 s;
  285 analyzed, two deliberate load failures, native invocation blocked.
  Zero shared `Cannot emit Zig type` exceptions. Fresh owned nREPL 54267:
  56/56 in 10.556 s, 277 exact producer hits, one pack, zero builds/standalone.
  Pack 4305 handlers / 4194 reused, 23624288 native / 54377163 debug bytes,
  ID `3eab96efde4a2be0e55910da0226895f5570ce1a9d482e76cdf4c76f32596942`.
  Stable identities: 28 common closures, four regressions ALL deliberate
  compile-failure lessons, 39 additions / one removal; no host regression.
  Remaining scopes: 93 host / 136 syntax-only / 57 compile-failure / 15
  cross-target / six runtime-failure. Host failed preparation is 21 operations:
  14 scope/result/phase, four comptime remainder, three variadic. Other 72 are
  unobserved, placement, mutation/representation, nominal or specialization gaps.
  Evidence `.tmp/learn-storage-proof-{aot-summary,classified,errors,bodies}-017.edn`.
  Preparation PID 2905 and owned consumer PID 20451 exited; user services intact.
  Next separate batch underway: compiler-backed destination profiles (four
  target rows) and shared generic/variadic routing (add 0/1/2), then focused
  actual-body restart checks. Do not rerun this whole batch after tiny edits.
- Type-source proof repair frozen: five actual namespaces 65/71 -> 71/71,
  errors [], native invocation blocked, 5.999 s. Closure validation rejects
  detached lexical names (a/array/slice are not namespace operands); proof is
  a real declaration in the compiler dependency graph, not appended source.
  Anonymous source vs synthetic named type is compiler-proven false; preserve
  canonical prepared handler, don't claim nominal equivalence. Permanent proof
  gate three tests / 15 assertions, final fresh run green.
  Evidence `.tmp/type-source-proof-closed-017.edn`.
  Combined frozen normal whole API is now running with fresh in-process
  analysis; report `learn-storage-proof-017.edn`, accepted above.
- Shared `Cannot emit Zig type` cascade traced to the first invalid scoped
  adapter in test-comptime-evaluation, operation 20. A mutable capture's value
  descriptor was used as its storage type. Preparation now takes the exact
  compiler-observed address child and rejects comptime-only mutable storage
  before registering an adapter. Focused origin -> tuple -> while normal API:
  50/53, zero exceptions / invalid declarations; original perform-fn Zig test
  passes. Permanent scoped gate: ten tests / 79 assertions, fresh four-body
  producer/consumer exact-pack checks green. Whole closure count still pending.
  Evidence `.tmp/{first-emission-error-evidence,learn-scoped-comptime-storage}-017.edn`.
- `test_arrays` ID 59 singleton regression repaired: tuple-profile compiler query
  uses explicit caller in the existing emitter API. Actual 69/72 -> 70/72 with
  errors [] and nil-context calls zero. Permanent tuple/pointer-capture fixture
  four tests / 26 assertions, three actual bodies, fresh producer/consumer exact
  pack and zero builds/standalone. Not yet counted in whole closure inventory.
  All-native-image discriminator for shared emission error is green: selected
  tuple 16/16 and while 1/1 after exact all-lesson load order. Failure is in other
  handler preparation, not namespace-image loading alone. First-failure capture
  identified the invalid scoped adapter above; don't repeat the broad gate unchanged.
- Normal fresh API attempt: 2376/2741, 365 incomplete, 317.217 s; NOT accepted.
  It restores 14 anonymous-constructor preparation rows lost by the saved-analysis
  shortcut (original lexical source metadata is required). Strict fresh owned
  nREPL 53414 check passes 56/56 in 10.841 s: 277 exact hits, one producer pack,
  zero builds/standalone. Pack ID:
  `856c41bb4d2b00cb1a72c1bb974e207f51f290e73323f694e6587c27c42f7d0d`.
  It has 4245 handlers, 23269552 native / 53530233 debug bytes. Stable identities
  vs accepted whole: 27 closures, 61 regressions, 39 added, one removed; not a
  new accepted delta. Shared leaf failure captures 347 events, literal descriptors
  used inside emitted pointer types; isolated full two-namespace API is green.
  All-retained-image interaction being isolated, no Clojure type inference.
  Evidence `.tmp/learn-lexical-control-fresh-{aot-summary,classified,errors,bodies}-017.edn`.
  Owned check server 61039 exited; user services untouched.
- Current combined attempt is NOT accepted: 2362/2741 candidates, 379 incomplete;
  stable identities 17 closures / 64 regressions / 39 additions / one removal.
  All 56 selected authored bodies return correctly through fresh real nREPL,
  but nine builds violate cache acceptance (zero standalone). Exact failed
  tuple/scalar signatures prepare in isolated replay. Investigating all-load
  state versus serialized/restored-analysis context; normal API fresh-analysis
  comparison is running, not another broad regression rerun. Evidence root
  `.tmp/learn-lexical-control-{aot-summary,classified,bodies-failed}-017.edn`.
- Matching current HTML gate green: 291 comparisons, 288 transcripts / zero
  missing, 229.710 s; 14 browser tests pass in 27.656 s. Served/built hash
  `37a8c57bc64d87e4dc68d665dacfa8836d1765347eafc8910296be249444c915`.
  Existing preview reloaded, 302 annotated code panels, no native titles;
  pointer-local x has actual ZLS generated-Zig i32. No screenshot claim: hidden
  in-app viewport has zero width; maintained Chrome tests verify real layouts.
- Frozen broad emitter/runtime/bundle gate now passes 194 tests / 3,337
  assertions, zero failures. Whole handler planning reached all 285 analyzed
  namespaces; final bundle linking and strict 56-body consumer remain pending.
  Matching whole HTML regeneration is running once against these frozen edits.
- Permanent source-only metadata parity regression passes five tests / 29
  assertions, with five lexical bodies plus four direct pointer/block outputs,
  50 exact producer-pack hits, zero builds/standalone. The earlier explicit
  tag demand-root mismatch is fixed; whole acceptance still pending below.
- Frozen tagged-union strict gate green: two unchanged bodies, 14 exact hits /
  one loaded producer pack / zero builds/standalone. Explicit union tag layout
  now prepares under its actual frozen constructor-demand snapshot; generic
  dependency traversal keeps unused layouts lazy. Pure four tests / 21
  assertions green. Whole handler preparation launched from fresh compiler
  analysis; source-only native fixture regression also running.
- Expanded UTF-8 regression gate: five tests / 87 assertions green, covering
  byte slices and explicit optional/error-union byte-slice payloads at varied
  lengths, unchanged sentinel/comptime source, values lesson changed runtime
  data, and strict fresh constructor bundle reuse. The optional case previously
  compiled one length-specific conversion; shared owned encoder removes it.
- Constructor-cache regression fixed: explicit `[]const u8` JVM string coercion
  now uses the existing owner-scoped UTF-8 slice encoder rather than generating
  `[N:0]u8` backing/coercion handlers per length. Other string targets keep their
  original path. Two focused tests / 25 assertions green, including fresh
  producer/consumer exact-pack parity and empty/ASCII/Unicode warm reuse with
  no launched processes. Earlier four coercion builds are eliminated.
- Borrowed compiler-child metadata gate green: five lexical bodies plus direct
  pointer/deref/inner-block outputs, 50 exact hits / one producer pack / zero
  builds/standalone. Three tagged adapter misses closed; tightened source-only
  load still exposes one enum type-binding frozen demand-root cache mismatch.
  Final whole handler plan awaits this last parity fix; no count extrapolation.
- Fresh compiler-only analysis finished in 98.983 s: 285/287 namespaces,
  two deliberate load failures, zero analysis failures, one ignored quota
  lesson. Native invocation blocked. Its 2,892 observations are an analysis
  inventory, not new prepared coverage; final handler preparation is pending.
  La Professeure's separate regression suite passes 14 tests / 131 assertions.
- Final parity work: namespace-relative versus imported reference metadata in
  scoped parameter identities; compiler-confirmed reflected type-expression
  variants; borrowed pointee readers retaining their exact type/module/owner.
  Earlier broad emitter/runtime/bundle run: 194 tests / 3,337 assertions, two
  failures in one constructor-cache regression, correct outputs but four new
  coercion builds. Focused rerun underway; no blanket green claim.
- Combined acceptance underway: fresh whole compiler analysis, four bounded
  workers, native invocation blocked. Prior schemas are rejected globally:
  imported jvm_result.zig bytes and observer metadata changed. New checkpoint
  `.aguafria/precompile/learn-lexical-control-analysis-017.edn` is analysis-only,
  NOT prepared coverage. Final handler plan follows once parity fixes freeze.
- Actual five lexical lessons now 5/5, 46 exact hits / one 121-handler pack /
  zero builds, 3.314 s. Combined permanent fixtures five tests / 52 assertions.
  Direct inner pointer subforms still build three reader/layout dependencies;
  enclosing native switch success does not close those direct cache rows.
  Actual seven control tests now 7/7 correct, but three builds remain: two
  scoped metadata identities and one reflected Tag comparison. Shared canonical
  planner/dependency fix in progress; no whole-count change claimed.
- Union payload encoder frozen: five tests / 25 assertions, including fresh
  optimized 42 -> 43, void, 64-alignment and retained slice storage. Inspection
  transformed-graph regression two tests / seven assertions, real compile-only
  imported provider proof. Mutable aligned address attrs omit false; immutable
  constness stays true. Address/owner regression 12 assertions green.
- Maintained restart checker now inventories 56 bodies across 26 lessons;
  four tests / 21 assertions green. It includes new controls, five lexical
  lessons and four whole destructuring mains. Final combined restart pending.
- First nine lexical operands frozen with strict fresh-JVM cache parity:
  permanent fixture two tests / 22 assertions; adjacent anonymous-container
  one / seven green. Actual four-Learn-namespace producer has 128 handlers,
  one pack f08177fd2484359f5b6d681c0b6189b7f951e484b68da7e218e6be6284f42df4;
  consumer 13 exact hits, one pack, zero builds or standalone helpers. Exact
  IDs: struct_name 2/5, aligned_struct_fields 1, locally_scoped_global_variable
  0/1, switch_tagged_union 2/3/4/9. Original native signatures stay unresolved;
  separate JVM signatures are Zig-confirmed, nominal-equivalence? false.
  Synthetic typeName preserves ordinary JVM behavior, not native nominal
  equality. Enclosing print/instance operations remain open. Independent
  review found no actionable first-nine defects. Whole headline remains the
  accepted 2387/2703 until one frozen multi-fix checkpoint. Evidence root
  `.tmp/nominal-direct-container-evidence-017.edn` and reproducible gate.
- Destructuring dependency fixes frozen: permanent three tests / 23 assertions
  green, including two fresh JVMs, typed u32/i8 children and retained tuple
  owners. Actual four unchanged main bodies 4/4, 28 exact pack hits, one pack
  `c2a01fe1c90aee82dc22312be978939612ba966f0339a761546a2b422bf41f98`,
  zero builds or standalone helpers; latest consumer 2.024 s. Compiler-owned
  tuple queries memoized by source/dependency identity: two queries / 465.585 ms.
  Evidence `.aguafria/precompile-tests/destructuring-baseline-6416697555420002157`
  producer-summary.edn, consumer.edn, result-schemas.edn, permanent-tests-2.log.
  Four assignment rows are placement accounting, not new cache closures.
  Worker 50066 / PID 25959 closed; subsequent transport-resource changes
  require a new combined pack for final acceptance. Whole count not updated.
- Destructuring cold baseline narrows six suspected rows to two real cache
  defects: four mixed/to-existing assignment rows already have zero builds.
  Four unchanged main bodies return correctly, but block tuple compiles four
  index dependencies and return-value tuple compiles two literal print adapters
  after its u32 elements are decoded to raw JVM integers. Producer 40.612 s,
  one 97-handler pack; consumer 7.873 s, 19 hits / one pack / six builds /
  zero standalone disk hits. Generic tuple schema/dependency fix underway;
  no closures claimed. Cache root `.aguafria/precompile-tests/destructuring-baseline-7657942651568634757`
  contains producer-summary.edn, consumer.edn and result-schemas.edn.
- Scoped-control follow-up underway: switch/while structural functions must
  capture syntax as macros, using existing scoped bridge. Explicit result role
  distinguishes switch statements and loops without :else-expression; try in
  statements retains an error-union void result. Seven focused tests / 42
  assertions pass, including unchanged native source, label/value shadowing,
  while error-capture else-expression and contextual control-result syntax.
  A propagating statement throws its native error so later ordinary forms
  cannot hide it. Actual six-lesson consumer 6/7 passes but has three builds;
  no strict acceptance. Pointer-union failure is constructor {:ok 42} decoding
  zero before native switch mutation, not capture copying. Generic encoder
  fix delegated separately. Consumer extraction now rejects missing bodies
  (initial syntaxquote-qualified selector selected none, not acceptance).
  Reuse producer only while source unchanged; transport resource edits mean
  final combined regeneration is required. Native scoped fixture gate running.
- Earlier lexical development history: ordinary JVM/preparation
  share anonymous-type-plan registration; explicit initializer provenance feeds
  a separate compiler query marked ordinary-jvm / nominal-equivalence? false.
  Native signatures remain original; JVM signatures recorded separately. Nine
  focused operands prepare. Empty-cache fixture strict gate passes, but actual
  four-Learn-namespace restart still compiled two string readers (11 pack hits,
  correct outputs). The query must use ordinary named-mode logical typeName
  emission, not clean inspection's pointer-to-array result. New exact-mode
  producer/consumer pending. Owned worker 49625 / PID 15471, root port metadata
  restored to 55615. Compiler/source edits follow the accepted HTML snapshot
  below; do not call that snapshot acceptance of unfinished lexical changes.
  No Clojure type inference or reconstructed native nominal equality.
- Frozen support-only parsing optimization: 17 tests / 137 assertions green.
  Five samples, same 24 cached artifact keys and same pack/cache: median cached
  finish 1081.167 -> 78.638 ms (92.7% less), AST/version calls 24/24 -> 0/0,
  zero native compiler calls both. Cached preparation 30.671 -> 25.126 ms;
  2400 direct lookups 125.138 -> 133.807 ms, so no runtime lookup win claimed.
  Only supplier-validated fragments are masked for the extern trigger; original
  import/export and asset eligibility remain intact. Permanent regressions
  retain user extern/link checks, same-name externs outside support, mismatched
  supplier/cache identity, UTF-8 and test assets. Native sources/keys unchanged.
  Owned workers exited. Evidence root `.tmp/bundle-support-ast-{gate-result,
  perf-baseline,perf-after}-017.edn` and their reproducible scripts. This is
  focused preparation performance, not a whole-project speedup measurement.
- Maintained post-preparation gate: `learn.bundle-cache-check` plus unit tests
  added under `test/learn`; ordinary selected bodies evaluated in fresh owned
  nREPL 49835. 40/40 pass in 8.423 s, 189 exact hits / one producer pack /
  zero native builds or standalone JVM helpers. Same accepted whole pack ID
  as below. Failed error-union returns are rejected even if no JVM exception
  was thrown; fresh-state and exact bundle guards fail closed. Units two tests
  / 16 assertions green, including the expected body-count guard. Evidence
  root `.tmp/learn-maintained-cache-{check,unit}-017.*`.
  This is a 12-lesson regression gate, not all Learn subform coverage.
  Owned worker PID 19768 / 49835 exited; user 53717 / PID 89334 remained live
  and the Learn port file is restored to it.
- Read-only next-host grouping of saved placement/unobserved rows: 13 viable
  dependency/control follow-ups (not established closures). Six destructuring
  dependencies: mixed assignment ID 6, to-existing 5/7/9, block tuple 10,
  return-value tuple 4. Existing JVM paths are tuple Seqable/Indexed reads and
  vector `assign!` RHS snapshots; prepare those same real-compiler-derived
  conversions/storage dependencies, preserving swaps/discards/ownership.
  Seven self-contained controls: tagged union 7/10, modify union 7, inferred
  errors 14, merging errors 11, dispatch 27, while-else 3. Reuse scoped plans,
  compiler captures and explicit statement/result distinction. Four parent
  exit controls, 17 range fragments, five generated bindings and one deliberate
  target compileError are not independent runtime adapters. Host-unobserved
  29 = 24 compiler-pruned constant/target/specialization branches plus five
  unused generic add-explicit operations. Saved Zig contains their probes;
  compiler logs omit them. Do not force unreachable code or guess signatures.
- Latest accepted frozen checkpoint: 2387/2703 prepared, 316 incomplete;
  285 analyzed / 287 attempts, two deliberate load failures, zero analysis
  failures, one ignored quota lesson. Native invocation blocked. Warm replan
  575.341 s: 243 previous compiler analyses reused with exact regenerated
  artifact hash equality, 42 reanalyzed. Compared with the accepted scoped
  checkpoint: four common closures, zero regressions, zero additions, four
  contextual-address observations removed (not closures). One whole bundle
  `3b1eb2726aa7fec64f1693fd32a9a5752d6b5079cd230be2279eabbc912b2d70`:
  4115 handlers, 3544 reused, 22,652,464 native / 52,270,744 debug bytes.
  98 explicit exclusions: 67 native-test-context, 27 external exports,
  four panic profiles. Fresh consumer passes 40/40 ordinary actual bodies
  in 8.643 s: 189 exact hits, one producer pack, zero builds or standalone
  JVM helpers. Seven analyses and one semantic validation still occur;
  do not describe the gate as no compiler work whatsoever. Evidence:
  root `.tmp/learn-wrapper-link-resumed-{aot-summary,classified,bodies}-017.edn`;
  public `.aguafria/precompile/learn-wrapper-link-resumed-017.edn`.
- Matching frozen-code HTML: 291/291 outcomes, 288 transcripts / zero missing,
  14/14 browser tests. Whole pipeline 249.382 s, browser 27.198 s; built and
  served SHA256 `6ef3c047177c2d489fd86eef37f644adda9ba4236f7143ef9a18e225d9a545fe`.
  Existing in-app tab reloaded; 302 code panels have type reports and zero
  type tooltip elements carry native `title` attributes. Evidence:
  root `.tmp/learn-wrapper-link-resumed-{html,browser}-017.*`.
- Remaining 316 scope split: 106 host, 135 syntax-only, 53 deliberate compile
  failures, 15 cross-target, seven deliberate runtime failures. Categories:
  53 compiler-rejected roots, four comptime/representation, 29 failed handlers,
  53 inspection placement, 23 nominal/non-runtime, two specialization,
  152 unobserved. Two examples per category are in the audit report. The
  link policy correctly moves four unsupported external calls to failures;
  the discarded 2391 headline below was never accepted AOT readiness.
- Completed performance batch rationale: avoid parsing support-only graphs for extern checks
  after validating/masking the exact supplied support fragments. Retain
  original import/export facts; actual user extern declarations and asset
  expressions still require the compiler parser. Focus supplier mismatch,
  UTF-8, user function/variable, same-name outside-fragment, and cached finish
  regressions. The sampled producer stack shows redundant AST/helper work;
  the measured frozen result is above. No whole audit per small edit.
- Link validation frozen: 10 tests / 81 assertions green, broader runtime
  77 tests / 2469 assertions green. Exact compiler-owned support-fragment
  provenance includes its actual command supplier and cache identity; user
  extern declarations outside those spans still require real linking. AST
  checks actual `keyword_extern` (not export/prototype token truthiness), with
  UTF-8 span regressions. Current replan reuses prior compiler observations
  only where regenerated startup artifact hashes exactly match; all other
  namespaces get real Zig analysis. No production resume API. Producer/report
  root `.tmp/learn-wrapper-link-resumed-*-017.*`, public
  `.aguafria/precompile/learn-wrapper-link-resumed-017.edn`; whole pack,
  40-body strict fresh consumer and matching HTML/browser gates passed above.
- Next shared host batch (read-only plan, NOT closures): 19 lexical container
  rows across `result_type_propagation` ID 4, `struct_name` 2/5,
  `test_aligned_struct_fields` 1/4/9, `test_locally_scoped_global_variable` 0/1,
  packed struct/union equality ID 1 each, and `test_switch_tagged_union`
  2/3/4/5/6/8/9/11/12. First gate nine direct type operands. Separate four
  `test_anonymous_struct/check` receiver-specialization rows (7–10).
  Preserve `.tmp/anonymous-struct-identity-017.edn`: reconstructed `@Struct`
  is NOT nominally equal to either original type; don't repeat that failed
  approach. Extract ordinary JVM anonymous-container descriptor planning,
  then let Zig confirm its actual synthetic representation in a separate
  inspection copy; never relabel it as the original lexical native type.
  Both ordinary demand/preparation must choose identical declaration keys.
  Original native emission and lesson source stay unchanged. Start only after
  current frozen checkpoint, with blocked native execution + owned storage,
  alignment/union/static/equality/typeName regressions and strict fresh pack.
- Previous rejected attempt, NOT accepted AOT readiness: whole compiler analysis
  reaches 2391/2703 (312 incomplete), 285 analyzed / 287 attempts, zero analysis
  failures. Stable identities show eight prepared-stage closures, zero
  regressions and four removed contextual-address observations. Bundle link
  fails on authored external symbols in five namespaces; four apparent
  operation closures lack actual link proof. Preserve the completed compiler
  observations and replan handlers after fixing link validation. Required
  policy: user/external declarations trigger real linking with configured
  inputs before collection; exact compiler-owned support fragments supplied
  by the command's support artifact retain cheap semantic validation. No
  symbol-name whitelist, disabled linker checks or split packs. Latest usable
  whole pack at that time was the 2383/2707 checkpoint below. Evidence:
  root `.tmp/learn-wrapper-publication-{classified,aot}-017.*` and public
  `.aguafria/precompile/learn-wrapper-publication-017.edn` (failed bundle).
- Historical HTML checkpoint before that link-validation edit passes 291/291 outcomes,
  288 transcripts / zero missing, browser 14/14. Total pipeline 268.325 s;
  served/built SHA256 fce6d2b4d779d5df076c708e06965d932f038af1b5501c0cd9c8825923da4d23.
  That fingerprint predates the accepted frozen checkpoint above.
  Evidence: root `.tmp/learn-wrapper-publication-{html,browser}-017.*`.
- Frozen aggregate-address follow-up: three focused tests / 25 assertions pass,
  including six correct fresh-JVM results, compiler-derived backing type,
  owned storage, map/struct input, one producer pack and zero builds/standalone
  helpers. The unchanged Learn switch body returns 1 with the same strict cache
  gate. No lesson source changed. Preparation now follows the callable's real
  frozen image when requesting nominal layouts. Two literal address rows defer
  to their callee; report those as denominator removals, not closures. Broader
  runtime units pass 77 tests / 2469 assertions. Whole AOT and current HTML
  verification are running once for this frozen batch, followed by a 40-body
  fresh consumer of the new whole pack. Evidence: owned `aggregate-address-2390800827483008043`
  and `learn-switch-address-12374796512670956170` reports;
  root `.tmp/learn-wrapper-publication-*-017.*` for the new checkpoint.
- Current follow-up, not a new whole checkpoint: function-source retention and
  ordinary native-wrapper bundle eligibility pass exact Learn do-op calls
  [11 -1], one 69-handler pack, zero builds/standalone JVM loads. Per-module
  Debug/ReleaseSafe modes remain intact; real compiler proof plus restart gates
  pass five tests / 32 assertions. Native enum lifetime/publication follow-up
  passes five tests / 41 assertions, including an empty-cache restart;
  unit checks pass 17. The 37-body restart remains green in 6.802 s with one
  exact whole-producer pack and zero builds/standalone helpers. Root cause of missing enum exports
  was retirement restoring state while lazy layout publication lacked its
  lock. Nominal accessors now live with callable ABIs. Source cache also tracks
  finalized type-accessor identities. Next: aggregate-pointer result context,
  then freeze this batch for one whole audit and current HTML verification.
  The prior page remains served, but core edits invalidate its outcome
  fingerprint; default-tool render retry correctly rejects all stale outputs.
  Evidence: root `.tmp/enum-{request-writer,accessor-lock-fixed}-017.log` and
  owned cache `learn-function-source-10694513550720692290` reports.
- Latest frozen whole checkpoint: 2383/2707 prepared, 324 incomplete; 285
  analyzed / 287 attempts, two deliberate load failures, zero analysis
  failures, one ignored lesson. Preparation 598.621 s, four bounded workers,
  native invocation blocked. Stable identities: 20 closures, three regressions,
  33 added observations, zero removals. One 3611-handler bundle, 609 reused;
  19,784,448 native / 48,075,288 debug bytes; 63 explicit exclusions (62 test
  context, one external export). Current fresh JVM: 37/37 actual bodies,
  166 exact hits / one whole-producer pack / zero builds or standalone JVM
  helpers, 6.711 s. Minimal follow-up uses Zig type equality to avoid a
  redundant conversion of an inferred allocator result; empty-cache two-JVM
  regression passes seven assertions. Whole count was not rerun for it.
  Page: 291/291 outcomes, 288 transcripts / zero missing, browser 14/14;
  built/served SHA256 f00e95f6cccc3cbc89ceb0720e239b8d0a41c797b537a654798a2c3b58a1907b.
  Evidence: root `.tmp/learn-scoped-reflection-{aot-summary,classified,bodies,html}-017.edn`,
  `.tmp/native-parameter-restart-017.log`. Next: exact native function source
  retention for `test_functions` add/sub2 and aggregate address context for
  `test_switch_dispatch_loop`; then batch host inspection/nominal failures.
  Do not claim all 324 incomplete items are host bugs; current host scope is
  111, syntax-only 136, intentional compile failures 53, cross-target 15,
  intentional runtime failures nine.
- Terminal defaults installed and verified: `/opt/homebrew/bin/zig` 0.17.0;
  both `/Users/pfeodrippe/.local/bin/zls` and `/opt/homebrew/bin/zls` point to
  persistent upstream eab2be0 ZLS 0.17.0-dev. Verified from root and Learn;
  old Homebrew ZLS was unlinked, not deleted.
  Default ZLS JSON-RPC initializes with no `AGUAFRIA_ZLS` override. User REPLs
  and preview server unchanged; no cache deletion or whole audit per patch.

- Previous whole checkpoint: 2335/2674 prepared, 339 incomplete; 285 analyzed
  of 287 attempts, two deliberate load failures, zero analysis failures, one
  ignored lesson. Warm preparation 293.193 s, native invocation blocked.
  Stable identities: nine closures, zero regressions, 17 omitted switch cases
  restored (not fixes). One 3485-handler bundle, 3439 reused; 19,082,832 native
  and 46,542,944 debug bytes; 45 explicit exclusions. Fresh JVM passes 17/17
  actual bodies in 5.159 s, zero builds/standalone helpers; its bundle ID is
  checked against the whole producer. Page retry passes 291/291 outcomes /
  288 transcripts / zero missing outputs; browser 14/14. First enum-export
  shared-REPL failure remains unexplained; isolated, repeated and lazy calls
  pass, so do not claim it fixed. Page SHA256 a9746f876a3c2bbae6d5943a604c494c179677807e57e6278eb83d411718e847.
  This precedes subsequent reflection/scoped edits. Evidence: root `.tmp/learn-scope-sentinel-*-017.edn`
  and `.tmp/learn-whole-bodies-017.edn`. No new whole audit per small patch.

- Frozen reflection/scoped follow-up: three lessons 127/127 runtime candidates,
  zero failed handlers or rejected probes; two result-context forms remain
  deferred to their enclosing casts. Ordinary fresh JVM passes all 20 bodies
  in 3.563 s: 65 exact hits / one producer pack / zero builds or standalone
  JVM helpers. Preparation 13.062 s; 184-handler pack, 182 reused. Denominator
  includes four explicit k/try operations and two restored statement blocks;
  do not add subset counts to whole counts. Focused gate nine tests / 57
  assertions; empty-cache scope and reified-field restarts seven assertions
  each; open-error and existing transport suite eight tests / 35 assertions.
  Storage provenance—not shared reader identity—authorizes same-image error
  copies. Independent open-error images reject explicitly, preserving the
  target; closed sets still remap names. No Clojure type inference.
  Evidence: root `.tmp/scoped-type-{lessons,bodies}-final-017.edn`,
  `.tmp/{scoped,reflection}-restart-gate-017.log` and focused suite logs.
  The whole checkpoint and HTML regeneration now complete as recorded above.

- Completed frozen follow-up: thirteen lessons 286/296, zero failed handlers;
  six regressions + one index gap close, and 17 switch operations are restored.
  Actual fresh JVM: 17/17 bodies / one bundle / zero builds or standalone
  helpers, 5.208 s. Compiler-observed string storage is prepared too; isolated
  empty-cache restart passes six assertions. Sentinel-slice lessons now 13/13
  (two more closures); fresh-cache restart eight assertions, real mismatch
  reports its intended native panic. Adjacent nine tests / 68 assertions pass.
  Whole preparation and HTML checkpoint completed above. Next is shared host
  inspection/nominal gaps. Targeted evidence at root
  `.tmp/{scope-error,sentinel-slice}-*-017.edn`; whole count below is not updated
  by adding subset counts.

- Previous whole checkpoint: 2315/2657 prepared, 342 incomplete; 287 attempts,
  284 analyzed, two deliberate load failures and one switch analysis failure.
  Warm preparation 344.954 s, native workloads blocked. One bundle: 3443
  handlers / 2699 reused / 18,850,112 native / 45,908,611 debug bytes, 45
  exclusions. Stable identities: 39 closures, six regressions, net 33 common
  improvements; 17 switch operations omitted, not counted as fixes.
  Page 291/291 outcomes / 288 transcripts / zero missing, browser 14/14 pass;
  fresh JVM red at 16/17 bodies, two compilations and one failed compilation.
  Current follow-up batches lexical retention, error-union construction and
  compiler-known type-table indices. Do not run whole audits per small edit.
  Evidence: root `.tmp/learn-comptime-context-{aot-summary,classified,bodies,
  html}-017.edn`; category table in `LEARN_AOT_AUDIT_2026-10-05.md`.

- Previous shared-cause subset (not a whole inventory): eight affected namespaces
  118/139 → 138/139, twenty verified closures; 157 prepared handler records,
  zero failed handlers; 25.055 s with native invocation blocked and warm
  artifacts. Remaining root is intentionally invalid naked `_start` → `abort`.
  Comptime type-table indexing now uses the ordinary call/result plan; native
  reflection decides comptime storage provenance. Grouped gate 13 tests /
  99 assertions passes. A stronger empty-cache, two-JVM ordinary-subform test
  and TLS restart pass together: two tests / 12 assertions in 65.101 s, correct
  aliases/constructors/TLS state, one bundle per fresh consumer, zero compilations
  or standalone JVM helpers. Named container
  self-initializers now emit consistently before/after Var interning. Next:
  batch host/local-type inspection gaps.
  Evidence: repository `.tmp/signature-final-{lessons,gate}-017.edn`,
  `.tmp/snapshot-guards-017.edn`, `.tmp/storage-and-tls-restart-017.edn`.
- Previous completed frozen-batch measurement:
  2293/2674 prepared, 381 incomplete; 287 attempts, 285 analyzed, two deliberate
  load failures and one ignored namespace. 545.157 s with existing artifacts,
  four bounded virtual-thread workers and native application invocation blocked.
  One bundle: 3254 handlers, 451 reused, 17,888,864 native / 43,683,337 debug bytes;
  45 exclusions (44 native-test-context, one external export). Alpha-normalized
  operation identities show zero additions/removals and four regressions.
  Evidence: `.aguafria/precompile/learn-batched-017.edn`, repository
  `.tmp/learn-batched-{aot-summary,classified}-017.edn`.
- Follow-up batch, not a whole-count replacement: native function references
  use the declared parameter's Zig coercion in both ordinary calls and preparation;
  private method-result queries retain their owner through canonical deref forms.
  Two focused tests / 22 assertions pass, including native function addresses.
  Actual two-lesson preparation closes
  the `do-op add`, `do-op sub2` and private `categorize` regressions; 19/22 prepare,
  zero failed handler records. An invalid comptime error-cast variant is still
  compiler-rejected and must not be hidden. Evidence:
  `.tmp/adapter-batched-regressions-017.edn`, `.tmp/adapter-real-lessons-017.edn`.
- Latest 14-body restart: all bodies correct, one bundle, no standalone JVM
  helpers; one TLS namespace compilation kept strict acceptance red. Its cause
  is private-adapter publication replacing an unchanged callable's snapshot.
  Snapshot preservation passes two focused tests / 18 assertions, including
  source-only/published demand paths and ABI/source change guards. Real TLS
  sequence now uses its original prepared callable key with zero compilations.
  Empty-cache, two-JVM integration regression passes six assertions in 31.082 s:
  correct thread-local state, one bundle, zero new compilations or standalone
  JVM helpers. Evidence:
  `.tmp/tls-image-key-trace-017.edn`, `.tmp/callable-snapshot-restart-017.edn`.
- Current batch page: 291 outcome comparisons, 288 transcripts, zero missing;
  HTML-only resume used pinned ZLS after the PATH 0.16 rejection, without
  rerunning outcomes. Browser 14/14 passes; existing IAB reloaded/visually checked.
  Built/served SHA256:
  26c267cbdb48d4f17b8d799a3d801c7cff7a045a4a933410a7446e55b81495b2.
  Evidence: repository `.tmp/learn-batched-html-017.edn` and browser log.
  Follow-up JVM adapter changes are subsequent to that page checkpoint.

- Preceding whole measurement: 2297/2674 runtime candidates prepared,
  377 incomplete, down from 387 with 16 net additional candidates. Common
  operation identities gain ten prepared entries; failed-handler operations
  29 → 22. Warm-artifact preparation: 225.923 s; native invocation blocked.
  One bundle: 3137 handlers, 17,237,200 native / 41,687,918 debug bytes,
  3098 reused. This precedes the current batched adapter/scope edits.
  Evidence: `.aguafria/precompile/learn-error-payload-whole-017.edn` and
  `.tmp/learn-error-payload-whole-{summary,classified}-017.edn` at repository root.
  Current category table: `LEARN_AOT_AUDIT_2026-10-05.md` at repository root.
- Earlier batch gates: compiler-confirmed imported factory result identity,
  method-result reader and type-equivalence preparation now pass the stronger
  five-body fresh-JVM restart: one bundle, zero builds or standalone helpers,
  8 assertions in 71.678 s (`.tmp/method-batched-restart-017.edn`). The preceding
  six-lesson 14-body trace is not rerun until whole-bundle publication.
  Converted for-else lexical scopes pass real Zig and reference-validation
  checks: 3 tests / 16 assertions in 3.258 s
  (`.tmp/scope-batched-gate-017.edn`). Tiger regeneration: 245 modules /
  4442 declarations / zero raw fallbacks; current catalog is authoritative
  for bulk loading, so unlisted files remain untouched. Fresh corpus/catalog
  load passes 2 tests / 23 assertions in 146.320 s
  (`.tmp/tiger-batched-load-017.edn`). Owned long-lived REPL's stale loaded-lib
  entry is not treated as a fresh loader failure or worked around.
  The whole preparation and HTML checkpoint completed as recorded above;
  follow-up patches use focused checks, not another whole audit.
- The post-enum HTML evidence below is historical; current core edits require
  a fresh page acceptance run. Do not rerun or report it after every patch.

- October 5 checkpoint (supersedes the older bundle/browser evidence):
  - Enum-to-union provenance is fixed without changing the lesson:
    `test_coerce_unions_enums.clj` now passes all four ordinary JVM body forms.
    Compile-only preparation covers 15/15 runtime operations; native execution
    is blocked. Current fresh consumer: 13 exact hits / one bundle / zero
    compilations or standalone JVM handlers in 2.623 s.
  - The real REPL then exposed stale enum readers after schema redefinition.
    Declaration lookups now identify their receiver schema generation; retained
    function results use their native image's enum accessors. Incompatible enum
    arguments are rejected before FFI. Function reevaluation adopts the new
    schema. Native member bytes and emitted names remain authoritative.
    Unsigned transport bytes are normalized to JVM bytes at the transport
    boundary. Current gate: 5 tests / 41 assertions pass in 73.226 s, including
    u8/u16/i8 boundary values and fresh-cache AOT / one-bundle restart.
    Adjacent cache guards: 3 tests / 23 assertions pass in 165.136 s;
    runtime: 72 tests / 2,442 assertions pass in 471 ms.
  - Final post-enum HTML verification passes: 291 outcome comparisons,
    288 REPL transcripts, zero missing outputs, 286.808 s. Browser 14/14 pass
    in 31.094 s. Built and served SHA256:
    a37f50160b70b0239335400eea8c169b7daa921323e4da2a95db9b77e462cc15.
    Existing IAB reloaded and side-by-side Hello World visually checked.
  - Full post-enum Learn preparation published: 2271/2658 runtime candidates
    prepared, 387 incomplete; 703.431 s with valid artifacts available, not cold.
    287 attempts / 285 analyzed / two deliberate load failures / one ignored.
    One3043-handler bundle: 16,658,560 native / 38,823,500 debug bytes;466reused;
    63 exclusions (62 native-test-context, one external export).
    Eight old failures prepare; seven operations gain failed carried-error
    variants alongside their still-prepared original handlers. Net improvement
    is one. Full categories with two examples: LEARN_AOT_AUDIT_2026-10-05.md.
    Fresh consumers of this exact whole bundle: values5bodyforms/nativeoutput
    match/32exacthits/onebundle/zerobuilds2.136s; enum4correctforms/13exacthits/
    onebundle/zerobuilds2.404s. No unapproved standalone JVM helpers.
    Tiger main489/644 remains an older snapshot, not current acceptance.
    Preparation planners were frozen;
    the subsequent byte-decoding fix does not run during compile-only preparation
    and changes neither generated Zig nor handler identity.
    Current evidence: .tmp/enum-coercion-gate-017.edn,
    .tmp/enum-coercion-repair-guards-017.edn,
    .tmp/enum-repair-runtime-gate-017.edn,
    .tmp/enum-live-redefinition-017.edn,
    .tmp/learn-enum-coercion-{aot,restart}-017.edn,
    .tmp/learn-post-enum-refresh-017.edn,
    .tmp/learn-post-enum-browser-017.log,
    .tmp/learn-post-enum-observations-{aot,classified}-017.edn,
    .tmp/learn-post-enum-{values,body}-restart-017.edn.
  - Next: carried-error boundary. Ordinary optional/error-union body evaluation
    reproduces failure: host Clojure try leaves anyerror!?i32 intact for optional
    unwrap. Other new variants include Thread.join and string comparisons.
    Preserve native behavior and do not discard variants to improve counts.
    Evidence: .tmp/learn-try-carrier-diagnostic-017.edn.
    Direct prefix-try prototype is insufficient: the transport adapter returns
    usize, so Zig correctly rejects propagated anyerror. Inspect the existing
    native error-union schema/view path for an explicit error/payload boundary;
    do not add Clojure compiler hooks or guess payload layouts. No production
    change from this prototype, and no carried-error closure claimed.
  - Shared bundle eligibility now preserves each handler's original root:
    exact `@import("root")` calls bind to that entry's original module graph.
    Real Zig AST spans distinguish test-only `@embedFile` from active assets;
    emitted tests are preserved, and test-only `@import` still stays guarded.
    Full gate: 26 tests / 257 assertions pass in 296.503 s, including fresh JVMs.
  - Actual Tiger Grid reader: 3/3 artifacts packed into one 336,176-byte library
    in 3.729 s. Fresh JVM resolves all 78 exports from one bundle in 165.257 ms,
    without compilation. Preparation blocks application execution. This is a
    targeted check, not closure of all 502 historical asset exclusions.
  - Earlier HTML (superseded above): 291 outcome comparisons pass; 288 transcripts; zero missing
    outputs; 231.457 s. Browser 14/14 pass in 27.580 s. Built and served SHA256:
    f671da083663ac6e31284753ace3c5dd16c6d2e67d4d6980f08d96d97d20dd4f.
    Existing IAB reloaded and side-by-side Hello World visually checked.
  - The older enum diagnostic (2/4 JVM body forms failing) is superseded by
    the enum preparation, restart and schema-reload checks above.
  - Evidence: .tmp/root-bundle-production-gate-017.edn,
    .tmp/tiger-root-reader-bundle-017.edn,
    .tmp/tiger-root-reader-bundle-restart-017.edn,
    .tmp/learn-post-root-bundle-refresh-017.edn,
    .tmp/learn-post-root-bundle-browser-all-017.log,
    .tmp/learn-enum-union-jvm-diagnostic-017.edn.

- Latest verification checkpoint:
  - Production constant/coercion and storage-free-reader repairs are adopted.
    Gate .tmp/constant-coercion-production-gate-017.edn: 6 tests / 88 assertions
    pass in 32.925 s, including a new-cache compile-only AOT producer and fresh
    JVM consumer with one bundle and zero compilations. Immutable checked
    constructor provenance survives narrowing; widening reuses typed handlers;
    mutable, runtime-function and out-of-range narrowing still fail in Zig.
    Current-code coercion guard rerun PASS6tests88assertions38.058s:
    .tmp/constant-coercion-final-gate-017.edn. The earlier 6/88 gate predates
    the subsequent shared peer/try preparation repairs.
    Actual Learn targeted AOT: 4 namespaces, 69/72 runtime candidates prepared,
    zero failed handlers in 10.984 s. Remaining three switch-range placements
    in error_union_parsing_u64.clj lines 9–11 are syntax, not callable values.
    test_coerce_large_to_small 3/3, test_wraparound_semantics 11/11, values 28/28;
    error_union_parsing_u64 27/30. Native execution blocked throughout preparation.
    Public report: .aguafria/precompile/learn-constant-production-017.edn;
    summary .tmp/learn-constant-production-aot-summary-017.edn. Fresh affected
    ordinary-body consumer PASS3.363s: eight actual forms across these four
    lessons, correct output, 52 exact target-bundle hits / one bundle, zero
    compilations or standalone JVM-handler hits. Its first pass exposed three
    misses; the shared peer planner now retains typed-handler identity for
    Zig-declared comptime_int results, and compile-only probes prepare the
    payload and JVM-carried error union around Clojure try. All schemas come
    from Zig; native emitted behavior is unchanged. Latest production emitter,
    peer and try gate83tests526PASS31.558s, with aliases/shadowed bindings and
    a new-cache two-JVM zero-build regression. Adjacent/private-type gate
    77tests2475PASS111.630s. Evidence .tmp/learn-constant-production-restart-
    017.edn, .tmp/try-peer-production-gate-017.edn and .tmp/coercion-adjacent-
    production-gate-017.edn. All8 touched standard Clojure source/test files
    pass cljfmt check; git diff check PASS. Latest HTML refresh PASS230.239s:
    291 outcome comparisons/288transcripts/zero missing; browser14/14PASS
    27.576s. Built/served SHA256b02543d85799ba617fc3d69a164ef818b03de2916777320fd55f023f89c8d0a5.
    Live IAB reloaded and side-by-side hello visually checked. Evidence:
    .tmp/learn-post-constant-refresh-017.edn and
    .tmp/learn-post-constant-browser-all-017.log.
    No post-repair whole Learn count until full inventory refresh.
    Corrected Tiger main inventory finished76min45.696s: 645operations,
    zero rejected roots;489/644runtimecandidates prepared155incomplete,
    821prepared/78unsupported/0failedhandlerrecords. One810-handler bundle
    published;502artifacts excluded for relative-or-dynamic-assets. Exact
    categories:58non-runtime/nominal,50placement,37unobserved,9rejectedprobes,
    1comptime receiver. A further handler-only type-declaration entry is excluded
    from the644runtime total. Evidence .tmp/tiger-corrected-main-{aot,inventory}-
    017.edn and public .aguafria/precompile/tiger-corrected-main-017.edn in
    examples/tigerbeetle-agua. This is main only, not the historical whole2030
    inventory; loaded Clojure planners predate peer/try changes. No post-repair
    acceptance claim. Ownedworker30439 finished normally; native freeze lifted.
    Next inspect concrete asset exclusions without restarting the full audit.
    This checkpoint supersedes the
    older production-pending/freeze notes below.
  - Corrected Learn full inventory has now published: 2270/2658 runtime
    candidates prepared, 388 incomplete, 617.131 s, native execution blocked.
    287 namespace attempts: 285 analyzed, 2 deliberately invalid load failures,
    1 quota example ignored. This replaces the historical 2264/2658 count.
    Exact purpose-enriched inventory: .tmp/learn-corrected-observations-
    classified-017.edn. Intentional upstream failures/syntax/cross-target cases
    remain separated from ordinary host gaps; they are not all library defects.
    Genuine red regression: `(k/u8 (k/u64 255))` fails in ordinary JVM evaluation
    although the same native Zig const expression succeeds. Permanent test
    typed-literal-coercion-retains-comptime-provenance currently 1 assertion
    passes, 1 error. Preserve compiler-confirmed comptime provenance without
    treating runtime or mutable values as constants. Production changes are
    still frozen until the corrected Tiger analysis/preparation publishes.
    Fresh-JVM consumer of this exact published Learn bundle PASS2.213s: all5
    values.clj body forms match native output,34exactbundlehits/onebundle,
    zero compilations/unmatched hits/unapproved standalone handlers. Evidence:
    .tmp/learn-corrected-values-restart-017.edn. Corrected Tiger whole count
    remains pending; typed narrowing red/prototype evidence is separate from
    this unchanged production bundle.
    Isolated typed-narrowing prototype2tests60PASS22.813s, including runtime
    function/mutable/out-of-range rejection and numeric identity checks.
    Native compile-only observer prototype exports the exact typed integer
    value for a narrowing cast, but only the operand type for runtime widening;
    no Clojure type guessing. Compiler-derived bundle/restart prototype PASS:
    native execution blocked during preparation; fresh JVM returns255 in616ms
    with4bundlehits/onebundle/zero compilations. Evidence .tmp/typed-literal-
    coercion-{aot,restart}-prototype-017.edn. Adopt shared production fix after
    frozen Tiger snapshot publishes, then run permanent regressions and targeted
    actual Learn preparation. Production implementation remains unchanged.
    Another confirmed preparation-only defect: imported math/maxInt/minInt
    return declared comptime_int values, but preparation asks for impossible
    address readers. Permanent regression storage-free-imported-results-do-not-
    prepare-address-readers is red1test6PASS3errors; ordinary calls pass. Isolated
    storage-free schema guard prototype1test9PASS563ms. Evidence .tmp/storage-
    free-reader-{red,prototype}-017.edn. Adopt alongside narrowing after frozen
    snapshot publishes; do not subtract prototype closures from whole counts.
    Narrowing implementation plan: retain a separate immutable coercion source
    in ZigValue state, not the operator planner's comptime-expression; preserve
    address owners and never stamp runtime ABI function results as literals.
    Factor numeric result ownership out of invoke-value! so native function
    results stay runtime. Keep ordinary operator signature reuse unchanged.
    In native operation_probe, use Zig integer signedness/width reflection to
    decide whether an @as needs a constant; encode the exact typed value at
    the call site only in that compiler-required branch. The observer should
    delegate such conversion observations to precompile-literal-coercion!
    plus normal scalar storage preparation. Native call-site prototype avoids
    passing a runtime value into a comptime helper for widening. Replace the
    old literal-arithmetic test's incorrect known-i64 rejection guard with an
    actual mutable/runtime i64 guard; permanent native-function-result guard
    already added. No per-example inferred types or unsafe @intCast shortcut.
    Native negative control confirms Zig also rejects narrowing echo(255)'s
    u64 runtime result to u8: .tmp/typed-runtime-coercion-negative-017.log.
    All3 touched permanent test/fixture files pass cljfmt; git diff check PASS.
  - Current October 4 checkpoint (supersedes old live handles below):
    Recursive reader production gate9tests53PASS50.578s; actual inline-else
    AOT14/18operations,4unsupported/0failedhandlers,18.707s. Native helpers
    are unfrozen; Ghostty strict consumer finished131.733s,4exacthits/one
    bundle/zero compilations before the reader edit. No startup speedup claim.
    Tiger regenerated245files/4442structuraldeclarations,zero unresolved
    syntax/fallbacks. Both previously rejected Grid/ReplicaReformat roots now
    compile(exit0) with native execution blocked. Emission mapping defect:
    probe construction observed its own result-type expressions, shifting
    later operation IDs. Shared observer guard fixes this: actual Tiger642
    operations identical in full/empty/selective passes,zero differences.
    Regression1test8PASS536.630ms with actual full/selective Zig observations;
    cljfmt check PASS. Tiger1375/2030 is historical and its failure signatures
    are unreliable where IDs shifted: do not subtract targeted closures from
    it. Fresh four-operation compiler/preparation replay completed4/4prepared
    in82.377s; source-based selection, no old numeric IDs/signatures reused.
    Exact internal static-field and type-name readers no longer retain an
    unused container's public state surface; ordinary user reflection still
    does. Shared operand-reader preparation now includes a field's lazy type
    alias getter. Native laziness/explicit-test-only rejection2tests23PASS
    1.735s; fresh two-JVM onebundle/zero-compilation regression1test7PASS
    16.875s. Before repair it compiled exactly one alias getter. All6 touched
    source/test files pass cljfmt check. Adjacent namespace/bundle gate4tests
    29PASS57.419s; runtime72tests2442PASS485ms with exact-reader negative guards.
    Current corrected main inventory exec24369: regenerated245files in6.654s,
    4442 structural declarations/zero fallback/unresolved syntax; actual full
    main analysis/preparation running, native invocation blocked. Report will
    be tiger-corrected-main-017.edn; do not use historical shifted signatures.
    Learn corrected full inventory exec27224 runs in parallel (bounded2workers,
    native execution blocked), reportlearn-corrected-observations-017.edn.
    Shared core/native inputs are now frozen for these acceptance snapshots.
    Do not clear caches or mutate user REPLs; do not recount old signatures.
    Learn HTML refresh45.432s PASS291comparisons/288transcripts/zero missing;
    browser14/14PASS26.580s. Built/served SHA256
    a921132fcf027cc2c68f56dcf175313da890e9ec2fbc224de4a8356f5ab258f6.
    Actual IAB reloaded and visually checked. Latest refresh did not rerun the
    historical18JVMtests123assertions. Evidence .tmp/learn-post-recursive-
    refresh-017.edn, .tmp/learn-post-recursive-browser-all-017.log,
    .tmp/tiger-probe-mapping-fixed-017.log, .tmp/probe-identity-regression-
    017.edn. Next corrected Tiger observations/preparation, unused Snap-state
    activation, meaningful remaining gaps, final counts and affected restart
    gates. No near-finished or full acceptance claim. Latest evidence also:
    .tmp/tiger-probe-repair-replay-017.edn, .tmp/static-field-cache-red-017.edn,
    .tmp/static-field-cache-gate-017.edn, .tmp/lazy-field-reader-gate-017.edn,
    .tmp/static-field-adjacent-gate-017.edn, .tmp/runtime-bounded-reader-final-
    017.edn.
  - October 4 latest continuation: Tiger exec29375/PID87840 completed normally
    in 3699.756 s. Published baseline is 1375/2030 runtime candidates prepared,
    655 incomplete, 1976 prepared/329 failed handler records, 23/35 declared
    functions prepared (12 expected skips). This separate JVM loaded the old
    planner before this continuation's fixes: no post-repair acceptance claim.
    Exact inventory: .tmp/tiger-scoped-final-inventory-017.edn; full failures:
    .tmp/tiger-scoped-final-handler-failures-017.edn. Largest grouped failures
    include 135 unwrap-arity variants and 22 test-only Snap comptime assertions.
    Do representative targeted replays, not another full audit.
    Recursive-reader explicit Writer.Error prototype: 1 test/6 assertions pass,
    including public a/value and compiler inspection; preparation native calls
    blocked. Native helper still unchanged until Ghostty restart exec41837
    exits. Runtime tagged enum-union attrs fixed through the regular attribute
    normalizer; 72 runtime tests/2432 assertions pass in 435 ms.
    Loaded-pack exact-key reuse added to bundle lookup. Unit/native integration
    2 tests/81 assertions pass in 1.132 s. Ghostty prior fresh exec20948 has
    correct output and zero compilations but fails the one-bundle assertion
    after newer targeted AOT publishes overlapping keys (two packs selected);
    evidence .tmp/ghostty-queue-closure-restart-017.edn. New actual strict
    consumer exec41837/PID5700 running; no pass claim yet. Cold load 129.188 s
    remains unimproved. All five modified source/test files pass cljfmt check.
  - October4 continuation: addedactualstdreference regression(stdout/Thread.spawn/
    mem.print) andrecursive-sliceinspectionfixture; bothreproducefailures.
    Importedreferenceplanner nowpreservesactualreferencein result-reader-type.
    JVM testgatepending; nativehelpers/emitterstillfrozenforPID87840. That run
    loadedClojureplannerbeforeedit andis a pre-repairbaseline, notpost-fixproof.
    Coldloadper-fingerprintdiagnosticexec32928running; no perfclaim/adoption.
    Update: stdreaderreference+automaticreaderpreparation3tests20PASS17.490s,
    freshcache2-JVM exactbundle/zerocompiles gateincluded; Thread.spawn+mem.print
    ordinary/compile-only4PASS. ActualsavedZigreplay3ops8handlersprepared1.569s;
    targetednewAOThello+slices+threadlocalrunningin55615(imported-targeted-aot-job).
    No newwholeLearncountclaimed. Newper-fingerprintprofile122.696s:118022
    declaration-info calls; multiplefingerprintpasses pluswholeconvertedgraph
    dominatecoldload, notonegiantfingerprint. No printerprototypeadopted.
    TargetedAOTcompleted10.425s:3namespaces/49operationsallprepared,
    58handlersprepared,5declaredfunctions(1prepared/4expectedskips),0baseline
    failures,gaps,deferred. Publictargetreport .tmp/learn-imported-result-
    targeted-aot-017.edn. Runtimequeueclosurefix(bystabledeclarationkey) is
    source-onlyClojurechange; TigeroldJVMunchanged. Dense300nodeprototype
    sameoutput554.202->51.211ms; actualVSR/Multiversion/StateMachineclosures
    same. Runtime71tests2426PASS455ms. End-to-endperfnotyetmeasured.
    Recursivesliceinspectionregressionstillred: nativehelpereditdeferreduntil
    Tigerpublishes; explicitwritererrorcontractneeded,inferredsetsloop.
  - Learn full final-core AOT exec25404/PID6648 finished1255.309s:287attempted,
    285analyzed/2deliberatelyinvalidloadfailures,1quotaignore;2264/2658runtime
    candidatesprepared,394incomplete,2800uniquehandlerpack(15388096native/
    36193740debugbytes),72reusedentries. Publiclearn-scoped-final-017.edn and
    .tmp/learn-scoped-final-inventory-017.edn nowauthoritativeforthiscore.
    Freshvalues exec62814PASS2.183s:5actualJVMbodyforms matchnative main,
    34exactmanifestbundlehits/onebundle/0compiles/unapprovedstandalone.
    No broad0-errorsclaim: pendingimportednominalreturnreferencebug confirmed.
    TigerVSRcheckpointpublished1376candidates/1076prepared/300incomplete,
    nofailedhandlers; main646operationspreparedreportstillpending. Partial
    VSRcounts not addedto oldwholebaseline. TigerPID87840 sourcefrozen.
    Ghosttyfirstprinterprototype169.231s=noimprovement; second132.561s likewise
    notmaterialgain. SecondguardrejectedpackIDafterLearnpublishedsharedkeys;
    read-onlymanifestaudit verifiesall4exactkeys exist in originalGhostty AND
    selectedLearnpack,correctoutputs/onebundle/zerobuilds. No coreprinterchange.
    Ownedverificationchildrenended;55615anduserREPLs/previewuntouched.
  - Latest continuation diagnostics (not final coverage): partial121Learn
    namespace checkpoints721/919runtimecandidatesprepared,198incomplete;
    .tmp/learn-scoped-partial-inventory-017.edn retains exactcategories and
    up to2actualexamplespercategory. Currentruns stilllive. MainTigeranalysis
    finished646operations/2rejectedroots1440.938s; preparationongoing.
    Ghosttycoldloadprofile154.836s(JFR) isolates140.234s in converteddependency
    loading versus148ms compile-plan and144ms load-module. Threadtrace shows
    declarationfingerprintserialization. Byte-identicalprinterprototype
    .tmp/ghostty-cold-load-prototype-017.clj is isolated, no corechanges.
    FreshLearnrepro .tmp/std-result-reference-red-017.edn proves stdstdout
    result identity loses actual stdreference and invents Aguafriaowner. Fix
    call-result-expression/result-reader-type after frozenrunspublish; test
    both ordinary import calls and compile-onlypreparation. This is genuine,
    separate from deliberatecompile-errors and missingexternalobjects.
  - Scoped result context continuation: actualmain319 now fullyprepared from
    fresh Zig inspection,1/1 observed signature67.606s,nativeexecutionblocked.
    Multiversion is a struct and .single_release is a shorthand constructor
    function, not a union tag. Nine-case main replay group closed; no addition
    to old fullTiger totals. Generic scope plan accepts actual Zig destination
    type; outer casts use the same scoped preparation plan. JVM1test6PASS5.373s.
    Expanded fresh two-JVM/named-enum gate2tests16PASS41.443s,onebundle/zero
    builds; adjacent152tests2933PASS23.038s; observer/nilcontext2tests9PASS.
    Formatter applied. ActualVSR1390 freshinspection91.267s yields contextnil
    (nominal type exists only in local scope). Correctly unsupported; notclosed.
    Fullrecount/newLearnfingerprint refresh running; check handles below.
  - Current handles (October 4, supersedes historical entries below):
    Finalmarkertransport7tests100PASS74.609s; strict4scope(namedenumincluded)
    restart1test9PASS40.526s,onebundle/zero builds. Rebuilds resumed on frozen
    source; current handles supersede cancelled IDs below. Newfullcountpending.
    Resumed fullTiger exec29375/PID87840 writes
    tiger-scoped-final-five-017.edn; Learn exec2512/PID87859 writes
    scoped-context-final-learn-refresh-017.log; Ghostty exec86263/PID87877
    writes ghostty-scoped-context-017.edn. All three are running on frozen
    source. Use these handles, not the cancelled ones below.
    Learn exec2512 completed PASS:291 outcome comparisons,288 transcripts,
    zero missing output,18 JVM tests/123 assertions. New tooltip suite adds3
    browser regressions; current full browser14/14PASS28.553s. IAB reloaded
    and visually checked; built/servedSHA256
    2905948d8277b2c97e35b5e612c5901fbd4a31d9cf940e48ff7d88cb74f9416a.
    FullTiger/Ghostty runs remain pending. Browser evidence:
    .tmp/scoped-context-final-browser-017.log.
    Ghostty AOT exec86263 completed PASS487.419s:15/15 candidates,3/3
    functions,one96-handlerbundle(589392 native bytes,1326231 debug bytes),
    no standalone entries,nativeexecutionblocked. Fresh strict consumer
    exec16938 uses the new report; logghostty-scoped-context-restart-017.log.
    Inventory classifier .tmp/tiger-preparation-inventory-017.clj verifies
    exact operation category totals and retains two concrete examples each;
    validated on oldbaseline only. Apply to current fullTiger when published.
    FullLearnAOT final-core refresh exec25404/PID6648 writes
    learn-scoped-final-017.edn(.d); source-dirresources/learn/example,
    explicitquota-ignoreunchanged,nativeexecutionblocked,parallelism2.
    Ghostty consumerPID5350. Tiger currentVSRanalysis1430operations/
    zero root failures505.178s; preparationstillrunning. These are observed
    operations, not fullyprepared counts.
    Ghostty strict consumer exec16938/PID5350 completed PASS131.894s:
    outputs[1,73,79,4],four exactmanifestbundlehits,onebundle,zero compilations
    and unapproved standalone JVMhandlers. Three authorednamespace images
    load separately from disk. Cold load remains slow; do not claim startup
    latency solved. Evidence .tmp/ghostty-bundle-restart-017.edn and
    .tmp/ghostty-scoped-context-restart-017.log. Owned consumer exited.
    Next after frozen snapshot: remove stale Grid.RepairTableResult alias
    (only reference in vendor Zig is its declaration) and unused
    ReplicaReformat.done plus its otherwise-unused local Result union.
    done reads nonexistent result; main uses pending/format instead. Source
    references checked across vendored *.zig. Regenerate only affected modules
    and recheck actual rejected roots; do not invent missing members. These
    two root failures are not claimed repaired yet.
    Finalaliascleanuphello.clj a/k emitsbyte-identical Zig. Incremental full
    outcomes/HTML18.219sPASS,finalbrowser14/14PASS27.780s. ActualIABreloaded,
    canonical panel checked; built/servedSHA256
    bd0159a4bbb21c83458d6250ff4e14eb0a4f618ec7e32be0f62026abf5bc20e8.
    This supersedes the earlier HTML hashes only; same frozen core/native
    source for AOT jobs. Evidence .tmp/learn-final-alias-017.edn and
    .tmp/learn-final-alias-browser-017.log.
    Continuation: keep core/native source frozen until exec29375 and25404
    publish. Learn has288 source files,one explicitquotaignore; initial
    sequential load/test-check phasefinished and bounded2-worker analysis
    is producing checkpoints. Don't count log's namespace-finished counter
    as all completed jobs (load/analysis failures also write checkpoints).
    After reports: use preparation-inventory on actual newfullreports,write
    categorycounts/2examples,strictfreshTiger/valueschecks. Then stalevendor
    rootcleanup/regenerateaffectedmodules and targetedrootrecheck; standalone
    authored/test/asset entries must stay clearly distinguished from handlers.
    No nativecacheclear or userapp/session interruption. Goalactive.
    The broader transport gate exposed1failure/3errors in callable fields.
    Dedicated BoundMethod envelope now separates transportcontrol from ordinary
    struct snapshots. Stopped only owned66270/66293/69127 pre-final checks;
    exec23988/29532/77721 cancelled, not acceptance. Marker transport and fresh
    scope restart have passed before restarting final runs. Existing reports
    below remain historical. FullTiger oldbaseline file preserved.
    Subsequent Ghostty strict restart uses report ghostty-scoped-context-017.edn
    via -Daguafria.acceptance.ghostty-report. Root test futures finished.
    Owned55615/PID59848 preserved. Do not change core while these snapshot runs
    are active. Old Ghostty/Learn checkpoints below predate scoped changes.
    Private nested struct payload schema repair uses exactFieldType and existing
    compiler-layout reader. Repro before: optionalbytes+two mapconstructor
    errors; after2tests9PASS6.279s. Runtime+adjacent78tests2463PASS23.120s.
    Compile-only/privateemptycache freshrestart1test7PASS23.132s: correct
    optional/unionmaps+native13/23,one readerbundle,zero builds. Authornamespace
    images remain separate diskhits; no standalone JVMreader allowed. Exact
    manifestkeys checked. Compilerlayout's existing supported-type limits
    unchanged; do not claim every possible privatepayload shape is supported.
    Final Learn refreshexec18984 PASS291comparisons/288transcripts/zero missing,
    18tests123PASS; browser11/11PASS25.720s. Actual IAB reloaded and checked;
    served/builtSHA256f3b744b288347c5ae0b17e3aaf51c8a044461fb8ad9a80d20a928428ad7147f5.
    Ghostty strictrestartexec25732PASS142.888s,four exactmanifestbundlehits,
    onebundle,zero builds. All validation children finished; owned55615 kept.
    Next scopedunion319/VSR1390 expectedresultcontext then wholeTiger recount.
    Operator result-reader preparation now uses real Zig storageSchema logs
    and the ordinary reader planner. Focused5tests26PASS36.561s,including
    fresh two-JVM fixture/onebundle/zero builds/standalone hits. Actual Tiger
    concat reinspection+prep2/2operations,69signatures PASS81.199s; restart
    two correct strings,seven exactmanifestbundlehits,onebundle,zero builds.
    No extra main operation closure (176/186 already counted); separate reader
    warmth gap closed. Adjacent7tests134PASS48.366s. Clean identity-refinement
    reader observations now retained;
    rejected-pass schemas excluded. Learn final fingerprint refresh pending.
  - Previous checkpoint (superseded where stated above):
    Current round eight verified main preparation closures:526/528/52/230/51/
    545/176/186. Former nine-case preparation group now only319scopedunion.
    Actual concat reinspection/preparationPASS58.714s,17cluster+52process
    signatures,all69handlers prepared,nativeinvocationblocked. Cross-project
    ordinary restart hits operatorbundle but compiles four result readers;
    no zero-build closure for this separate reader-identity/preparation gap.
    545fresh61.217sPASS; optional-helperprivatechild2tests3PASS3.487s.
    Decoder still loses private optionalPayload/unionPayload schemas; open.
    Forwarded module type freshbundle1test7PASS18.336s; runtime70/2425PASS.
    Ghostty refreshed AOT15/15,3/3,one96handlerbundle459.924s; fresh strict
    restartPASS125.025s,four exactbundlehits,zero builds. Learn checkpoint
    PASS291comparisons/288transcripts/zero missing/18tests123; inspection-only
    resource change requires new fingerprint refresh, runningexec89755.
    Concat serialization quota reproduced by standalone Zig regression;
    helper now uses same bounded10M budget as other inspection helpers,
    application code unchanged. Concat/serializer3tests10PASS3.611s. Learn
    final fingerprint refreshexec89755PASS; browser11/11PASS26.999s; actual
    IAB reloaded/visuallychecked. Served/built SHA256bdaebf1f14d43b08...match.
    Validation childrenfinished; userREPLs/preview and owned55615 preserved.
    Next: scopedunion319/VSR1390context,privatepayloadschemas;
    reconcilewholeTiger. Reader pending notes below are historical.
    No wholeTiger-count claim/cacheclear. CheckAI_MEMORY beforecommunications.
    AI_MEMORY.md contains only communicated notes; no repeated explanations.
    Eager23/23 actualVSR replay PASS154.834s; fixture4/20PASS; runtime68/2418
    PASS(last rerun227ms). WholeTiger baseline completed1487/2029,542gaps,
    3603.910s on older core. Concrete56-case fresh replay25prepared/31incomplete
    PASS253.589s(native calls blocked):24new main closures, null alreadycounted.
    Object initializer repair3/21PASS, fresh isolated bundle1/7PASS25.956s,
    zero builds/standalone handler hits; actualVSR317 real Zig reinspection+
    normal prep1/1PASS45.385s(.tmp/tiger-object-constructor-017.edn).
    VSR saved-baseline325minus25verifiedclosures=300remaining, notwholecount.
    Adjacent13tests86PASS92.121s. Learn build/eager-import-acceptance-017 PASS:
    291comparisons/288transcripts/zero missing,18tests123PASS; browser11/11PASS.
    Ghostty current AOT15/15candidates,3/3functions,93handlers457.249s;
    fresh correct outputs/fourmanifestbundlehits/onebundle/zero builds121.496s.
    Previous HTML driver rejected251stale transcripts after concurrent core
    edits; guard not weakened. Earlier Ghostty consumer compiled2namespace
    images; superseded by new public AOT and strict zero-build gate above.
    Static string/keyword member dependency fix4tests28PASS37.935s (symbol
    access already worked); exact nested aliases and separate-JVM gates.
    Main29-case replay20prepared/9incomplete218.239s,20additional main closures.
    Runtime69tests2422PASS670ms. Union accessor uses actual Zig @FieldType;
    native/JVM2tests9PASS6.224s. Direct Clojure nested-native union payload
    construction exposed a separate missing-schema case, still open.
    Fresh9-case replay0prepared/9incomplete94.989s, no new closure: Format
    accessor error gone, then missing tigerbeetle alias; parse_args now reaches
    unregistered callable. StateMachineType/process_args graph edges present
    in cached AND current extraction: diagnose materialization, not typeguessing.
    Evidence .tmp/tiger-nested-union-replay-017.edn. Latest Ghostty strict
    consumer PASS121.835s: correct values/four exactbundlehits/onebundle/zero builds.
    Final Learn member/union acceptance PASS291comparisons/288transcripts/
    zero missing/18tests123assertions; browser11/11PASS25.548s. PATH ZLS016
    correctly rejected; resumed onlyHTML/tests with documentedmatchingZLS017.
    Actual IAB reloaded/visuallyinspected; served/builtSHA25672a5e7dcc15b193...
    Validation children finished; owned55615 preserved alongside user sessions.
    Next: source-materialization gaps and nestedpayloadconstruction;
    repair local-enum result context or mark honest unsupported; reconcile
    focused closures with wholepublication. KeepuserREPLs/preview/cache intact.
  - October 4 current continuation (older checkpoints below are historical):
    Ghostty final native VT 6,552 PASS/54 skips/zero failures; focused OOM71/71,
    WASM and standalone builds PASS. Four actual JVM/reload tests20PASS.
    Numeric comptime inspection import fix3tests20PASS; public Ghostty AOT
    3/3 namespaces,15/15 candidates,3/3 functions,one93-handlerpack,425.683s.
    Fresh consumer [1 73 79 4],four exact bundlehits,zero builds/unapproved
    standalone hits,126.372s cold. A newer consumer after eager-import changes
    failed on cross-namespace import-alias collision; fixed by per-module import
    extraction. Alias-expanded fixture/restart running; fresh consumer exec31591
    pending. Do not call earlier strict gate latest-core acceptance yet.
    Null conversion planner permanent2/18PASS36.811s; actual fresh VSR603
    compile-only prepares93.825ms. Eager-import prototype4/15PASS18.491s;
    bounded actual23 replay9prepared/14failed(test-only Snap assertion).
    Fresh public focused replay exec13459 now running to exclude warm-registry
    effects. Report .aguafria/precompile/tiger-eager-fresh-017.edn under Tiger.
    Whole Tiger exec63619/PID46213 remains running on its original core:
    four checkpoints1,060/1,385;VSR1,051/1,376,325incomplete;main645operations/
    2rootfailures/1,807.983s analysis. Main preparation/bundle pending.
    Latest Learn inspection-import checkpoint291comparisons/288transcripts/
    zero missing,18targetedJVMtests123PASS,browser11/11PASS27.913s;hash493754b7...
    Full HTML/actual browser refresh required after final eager/null changes.
    Current adjacent runtime suite exec73088; warm owned55615 future
    eager-import-verification (expanded6fixtures). User REPLs/preview intact.
    No goal completion, staging, commits or cache deletion this turn.
  - October 4 vendor compatibility/current-tree gates: removed dead Tiger
    SyncTarget alias plus three uncalled obsolete helpers; retained SyncStage.
    Two files regenerated 5.959 s, zero fallback/unresolved syntax. Fresh
    ordinary five-namespace precompile exec63619/PID46213 blocks native calls:
    three namespaces complete, 9/9 candidates; VSR analysis 1,429 operations,
    zero root failures, 479.458 s; VSR preparation/main analysis pending.
    Do not add old focused IDs to these changed-source counts. Ghostty's three
    DebugAllocator sites replaced with SafeAllocator; wider VT compile errors
    repaired using actual reflection/CRC/typed-array APIs. Zig fmt itself
    migrated remaining enum builtins; final src fmt check passes. Final 65-file
    generation completed 59.659 s, zero fallback/unresolved. Final standalone
    exec22791 and native VT exec23629 still running. The native VT run predates
    final formatter rewrite and needs final-source verification; earlier
    standalone exec95633/PID result and JVM 2/12 pass are not final acceptance.
    Correct four JVM test names in Ghostty core_test.clj; run after final build.
    Root report ZIG_0_17_0_MIGRATION_2026-10-03.md added. Core/Learn unchanged
    by these vendor fixes; no redundant HTML refresh yet. Owned55615 remains;
    preserve user REPLs/preview/shared cache and user-staged vendor changes.
    No commits, staging, cache deletion or goal completion this turn.
  - October 4 scoped-expression preparation: 1,070/1,386 prepared, 316 remaining,
    213 cumulatively verified repaired IDs (focused saved inventory, not whole
    Tiger publication). New closures: IDs 24/1153/1342, production replay 8.760 s
    with native invocation blocked. Shared JVM/precompile planner uses actual
    Zig capture/address schemas; lexical analysis supplies names only. Mutable
    storage passed by address. Implicit labeled-block returns and parameter
    shadowing fixed. Adapter call-site comments suppressed in implementation
    AND comptime wrapper, authored source comments retained. Fresh restart:
    1 test/8 PASS, 34.446 s; 5 scoped cases, changed inputs, one bundle, zero
    builds/standalone disk hits, exact manifest keys. Emitter 76/459 PASS;
    existing JVM labeled-block gate 1/6 PASS; adjacent 7/45 PASS, 13.174 s.
    ID 50's six actual comptime-int mutation signatures now explicitly
    unsupported (:comptime-only-mutation); classification, not a closure.
    Remaining: 139 nominal, 96 placement, 78 unobserved, 1 scoped local-enum
    context failure (ID 1391), 1 comptime mutation, 1 rejected upstream alias.
    Evidence .tmp/tiger-scoped-production-017.edn and scoped-* verification.
    Capture context boundary also fixed before mutable-address lowering:
    actual normal/test/normal values and Zig builtin.is_test, 1/9 PASS 29.007 s.
    No extra inventory closure. Earlier Learn refresh passed but predates this
    final change. Final refresh exec5683/PID31808 exited successfully:
    291 comparisons, 288 actual transcripts, zero missing outputs;
    18 targeted JVM tests/123 PASS. Outcomes 164.912 s, blocks 31.891 s,
    inlines 0.704 s, HTML 4.486 s, tests 4.968 s. Browser 11/11 PASS 25.999 s;
    actual in-app preview reloaded, Clojure output visible. Built/served SHA256
    8c6f9601b9cdae6708eb3c7ad34cb7d5fd8362863221262b7a198a62abaad3df.
    Evidence build/scoped-acceptance-017.edn; all nine affected core/test files
    pass cljfmt checks. Next: original local-enum result context (ID 1391),
    genuine specializations/identity access, whole Tiger publication/restart.
    Owned root55615 remains; validation children finished. User REPLs, preview
    and shared cache untouched. No goal completion or cache clear.
  - October4 context gate closed six IDs643/885/1362/1363/1424/1427:
    coverage1067/1386,319remaining,210verified repairs (saved focused inventory,
    NOT whole Tiger analysis/publication). Production replay6prepared11.542s,
    native invocation blocked, globaltestfalse. Shared path defaultnormal,
    only authoredtest/compilerrejection selects realtestenvironment. Separate
    test adapter/cleanup identities; native values retain context; staticmember
    lookup warmed with call. Fresh3tests18PASS77.493s, alternating normal/test
    no runtime builds. Adjacentprivate/structuralbundle/nativevalue9/80PASS75.164s.
    Latest Learn outputdriver .tmp/test-context-learn-017.clj finished:
    291comparisons/288transcripts/0missing;18targetedJVMtests123PASS. Blocks
    32.695s,outcomes164.355s,inlines1.243s,HTML7.795s,tests5.321s. Stale inputs
    refreshed, guards retained, matchingZLS017 used. Actual IABreloaded and
    Clojure output visible. Served/build hash
    8123eba97cc60fa42f9f1c635491412c41ae938adf8e156b368eaef24048a332.
    Reportbuild/test-context-acceptance-017.edn passed; verificationJVMexited.
    Remaining139nominal/100placement/78unobserved/1comptime-onlyassignment/
    1rejectedroot. Root55615warm; preserve userREPLs/preview/sharedcache.
    Read-only placement breakdown80object/11range/4with-block/3switch-stmt/
    2catch-capture. Do not recompile unchanged syntax as ordinary calls.
    Rejectedroot actualvendorvsr.zig:59 references sync.Target; sync.zig only
    exportsStage, cleanvendorcheckout. No inventedTarget/sourcefix made.
    Next: sharedscoped-adapter preparation with actualcaptures/application
    specializations; honestcomptime-mutationreason; wholeTigerpublication.
  - October4 real-test-context work: prototype six Tiger records643/885/1362/
    1363/1424/1427 prepared78.777s with native invocation blocked. NOT counted
    until generic context preparation and permanent separate-JVM restart pass.
    New discovery per-operation context/memo separation under verification:
    owned55615 futuretest-context-verification, .tmp/test-context-verification-017.
    Current coverage1061/1386,325remaining. Latest Learn refresh93235 exited0:
    291comparisons/288transcripts/0missing,100/12905PASS; browser27589 11/11PASS.
    Built/served hash12f3cd62cb5744f34d6ae842c0508a0ddf12f6d215f29b8ff8eeafe42e0f6e1e.
    Strict private/structural fresh bundle2/14PASS50.986s; loaderfinal1/5PASS
    86.817s (prior artifact22/23 had a transient fixture delimiter failure;
    corrected fresh child verified). Preserve root55615/userREPLs/preview;
    no cacheclear or goalcompletion. Next six production replay, then remaining
    contexts/whole Tiger publication. Earlier checkpoints below are historical.
  - October4 adapter TLS fix: JVM-only generated adapters use existing atomic
    per-invocation tracking; no per-image active_depth TLS. Authored Zig retains
    outermost recursion tracking and ordinary static emission stays unchanged.
    Owned nREPL55615/serverexec15400: emitter74/441PASS,runtime68/2418PASS,
    nativecounter1/9PASS(depths0/1/7/32,unwind0). Retirement/panic4/26PASS33.467s.
    Loaderfreshchild640distinctfilecopies ofoneactualadapter,PASS; not640types.
    Final artifact suite futuretls-artifact-final includes own-copy cleanup;
    Learncurrentcore refreshexec93235, build/tls-tracking-acceptance-017.edn.
    Coverage1061/1386,325unchanged. Need finish currentgates/browser check,
    then remaining true compiler contexts/wholeTiger publication. Preserve user
    REPLs60588/53717 andpreview63979. No cachedeletion/goalcompletion.
  - October4 final private/editor acceptance: fresh discovery70tests/537PASS
    393.296s; strict private bundle restart included. Fresh adjacent JVM/reload
    5/44PASS16.657s. LaProf safe JVM3/51PASS(no GUI/devices/playback).
    Learn currentcore291comparisons/288transcripts/zero missing,100/12905PASS;
    browser11/11PASS27.535s; actual IABreloaded/Clojureoutputvisible, served/build
    SHA2567069701677381326db5dfa964be974254aa57740c7c310eba1bf506a9892ca99.
    Verification exec12644/6345/53532/99206/22367 allfinished; no owned validation
    processes left from these. Root65284/PID52004 isgone(DYLD TLS exhaustion).
    Coverage remains1061/1386,325gaps; do not inflate after rechecks. Next:remaining
    genuinecompiler contexts, wholeTiger publication/restart, native-library TLS
    resource ceiling. Crashhas566images/exactly1sharedsupportlib; otool/nm show
    standalone expression adapter8-byte TLS active_depth generated by devcall
    tracking. Investigate eliminating per-image TLS for JVM-only adapters while
    retaining native-call retirement safety; no such emission change made yet.
    No types guessed, no cache deletion, goal staysactive.
  - October4 private nested/editor checkpoint:1,061/1,386 prepared,325remaining,
    204verified repaired IDs. Categories139nominal/100placement/78unobserved/
    7failed/1root. Saved focused inventory, not whole Tiger publication.
    Four actual Tiger1133–1136 reprepare5.592s with native invocation blocked;
    do not count twice. Native-only constants use registered reference metadata;
    all adapter memo guards now check actual registry existence after complete
    document replacement. Owned real nREPL repeat gate4executions/74assertions
    PASS10.021s. Native AST sample69,636bytes/5,061nodes:11–34ms native including
    launch;38–92ms complete parse;cached40.619ms/zero process. Helper remains
    raw Zig, already fast; not an Aguafria-authored analyzer or full AIR dump.
    Report NATIVE_TOOLING_AND_RELOAD_017.md. Fresh discoveryexec12644/PID17584;
    Learnexec6345/PID17583 regenerates outcomes/blocks/inlines/HTML/tests.
    Owned warm root65284/PID52004 crashed DYLD TLS pthread-key exhaustion;
    it is gone. Interrupted adjacent native checks rerun freshexec53532;
    LaProf safe JVM checks exec99206 with nrepl dependency (earlier41615 was
    a missing-dependency launch error, not an acceptance failure).
    Preserve user REPLs and preview63979. No cache deletion or goal completion.
  - Structural syntax checkpoint: 1,057/1,386 prepared, 329 remaining,
    200 cumulatively verified repaired IDs. Actual Tiger records
    649/717/1005/1147/1370/1423 reprepare in 3.851s, native execution blocked.
    Expanded separate preparation/runtime JVMs pass 2 tests/16 assertions
    in 29.434s: one bundle, zero compilations/standalone hits, exact keys.
    Assignment probes preserve Zig's destination AND original RHS type;
    precompilation uses the ordinary conversion/construction path. This fixes
    usize -> ?usize without guessing types or changing ordinary Zig emission.
    Remaining categories: 139 nominal, 100 placement, 82 unobserved, 7 failed,
    1 rejected root. Saved focused inventory only; whole Tiger publication
    is unfinished. Evidence: .tmp/tiger-adapter-context-backlog-017.edn and
    .tmp/structural-values-expanded-verification-017.edn.
    Current final Learn refresh exec75623; fresh full discovery exec46142.
    Both own their verification JVMs and exit on completion. Root65284 stays
    warm. Preserve user REPLs/preview; no cache deletion.
  - Pre-structural acceptance:65discovery tests/494assertionsPASS plus
    separately tested true-cold variadic+broad restart2/14PASS,zero builds/new
    libraries. Native probe5/5PASS,UTF8/generic2/10PASS. Final Learn100/12905,
    291comparisons/288actualtranscripts/zero missing;browser11/11PASS26.266s.
    Actual reloaded IAB shows Clojure output; served/build SHA256
    30e2de1bc51491a7004736182608b5ae5f4d8cc8407b6efced05f6fe47463ca8.
    Memoized schema experiment rejected:34.651s versus35.244s;no closures.
    Latest139nominal reinspection completes only with isolated raised quota,
    still0fullytyped. No production quota/memo change. Root65284/PID52004 warm.
    Structural assignment/multiline gates PASS2tests/15assertions21.616s:
    genuine separate preparation/runtimeJVMs,onebundle,zero builds/standalone
    hits/exactmanifestkeys,changed assignment operands. Evidence .tmp/
    structural-values-verification-017.edn. Added generic borrowed-pointee
    decoder preparation from actual Zig pointer schema. Normal Zig source
    unchanged; native calls blocked during preparation. Six Tiger operations
    now targeted by tiger-structural-values-verification; don't count yet.
    Final Learn refresh restarted after these edits; previous hash predates
    them. Current coverage remains1051/1386,335gaps;
    139nominal/106placement/82unobserved/7failed/1root. Six failures need actual
    Zigtest context(PRNG/Snap);one comptime mutation has no native storage.
    Existing old Markdown evidence reports are absent; don't recreate history.
    No cache deletion or wholeTigerpublication. Preserve userREPLs/preview.
  - Iterator checkpoint:1051/1386 prepared,335 gaps,194 repaired IDs;
    52 closures since387,54 since389. Actual Tiger715/716 prepared5.427s with
    native execution blocked. Fresh iterator2/13PASS23.478s:one bundle,zero
    builds/standalone hits/exact manifest keys. Full discovery exec91937 exposes
    variadic fixed-param indexing regression; fix now passes isolated nREPL1/9.
    Fresh variadic+broad restart .tmp/variadic-context-restart-017.clj pending.
    Remaining139identity/106placement/82unobserved/7failed/1root. Root65284 warm;
    preserve userREPLs andpreview63979. No whole Tiger publication/cache deletion.
    Latest accepted Learn browser11/11PASS27.233s; latest core needs finalrefresh.
  - Generic-context checkpoint1049/1386prepared,337remaining,192verified IDs;
    50closures since387 (52since389). Affected35records:2closed,33unresolved.
    Tiger parse_addresses750/765prepare2.423s nativeblocked; affectedbatch25.235s.
    Fresh3tests/16PASS22.658s,onebundleload/bundlehits/zerobuilds/nostandalonehits/
    manifestkeys. Native probe5/5PASS; targetedadjacent13/104PASS21.192s.
    Learnexec94504/PID27544finishedcurrentcorePASS291outcomes/288transcripts/
    zero missing/100tests12905assertions; reportbuild/alias-catalog-acceptance-017.
    Browser gatepending: systemnode lacksPlaywright; use existing dependency
    runtime, no packageinstall needed. Root65284/PID52004warm; preserve userREPLs
    andpreview63979. Iterator715/716prototype2prepared isuncounted; implement
    sharedread-onlycall-result identityplanning+freshreuse. No cachedelete or
    wholeTigerpublication. Savedinventory .tmp/tiger-adapter-context-backlog-017.edn.
    Remaining141identity/106placement/82unobserved/7failed/1root.
  - Alias/layout checkpoint1047/1386prepared,339remaining,190verified IDs;
    48closures since387 (50since389), not whole Tiger publication. Both rejected
    aliasprobes compileclean/prepared6.781s nativeblocked. Lazy catalog names,
    enclosing-root exclusions and declaredtarget parameters; fixture2/9PASS6.131s,
    native4/4PASS. Prior full discovery60/450had5fails/1error; signature13/128PASS.
    Private/namedtuple fixesPASS, slice1/10PASS zerobuilds, runtimetuplefresh1/3PASS
    44.626s. Root65284 alias-catalog-adjacent-verification completed61/470:
    469PASS,onewarmfixturelookupfailure,zeroerrors; signature13/128PASS. Unchanged
    fresh checkexec79131PASS1/8 in3.196s,zero runtime compilations. All owned
    verification childJVMsfinished. Learnexec64944finishedPASS291outcomes/
    288transcripts/0missing,100/12905 with matchingZLS; report
    build/alias-catalog-acceptance-017.edn. Browserexec54610PASS11/11 in27.265s.
    IAB Clojureoutputvisible; served/build hashf9098b638e3d1c1fd855483ae9fadd105e2a4444e02c119368b433f15a3404eb.
    Remaining143identity/106placement/82unobserved/7failed/1root. Evidence .tmp/
    tiger-alias-cycles-verification-017.edn andruntime-tuple-final-017.edn.
    No cachedelete. Preserve userREPLs andpreview63979; ownedroot65284 remains
    warm for targeted Tiger work. Next: compiler-only iterator identity prototype
    for715/716, remainingidentity/placement contexts, permanent fresh-lookup test
    isolation, then whole Tiger analysis/publication. Do not recount unchanged
    wholeprojects or include unverified prototype results in prepared totals.
  - Constant-container inspection checkpoint:1045/1386prepared,341remaining,
    188verified IDs.46newops/60handlers42.672s; native execution blocked.
    Root selection uses authored type metadata, Zigconfirms actualtype; imported
    namespace aliases not recursively forced. New native+fresh consumer2/20PASS
    24.883s,one bundleload/5hits/zero builds/newlibs/exactkeys. Nullschema repair
    Cfresh12PASS24.991s. Remaining143identity/106placement/82unobserved/7failed/
    2probes/1root. Evidence tiger-const-container-verification-017.edn and
    const-container-restart-tests/evidence-017.edn; fresh coverage reflects real
    authored call sites, not arbitrary additional external lookups. Root65284
    const-container-adjacent-verification runs full discovery/signature suite;
    Learn CLIexec57931 refreshes final fingerprint andHTML. Prior78427PASS
    100/12905,291comparisons/288transcripts, but sourcechanged afterward.
    No cacheclear/wholeTigerpublication; preserve userREPLs andpreview63979.
  - Comptime aggregate checkpoint: focused999/1386prepared,387remaining,
    142verified repairs. Remaining143identity/128unobserved/106placement/7failed/
    2probes/1root. ActualTiger1006+1008 prepare with native calls blocked;
    fresh fixture13bundlehits/one load/zero builds/newlibs/exactkeys,26PASS29.513s.
    Evidence .tmp/comptime-receiver-cache-verification-017.edn and
    tiger-comptime-receiver-verified-017.edn. Root65284 comptime-adjacent-verification
    runs core/signature and then live tests; LaProf CLIexec62350, Learn CLIexec76870
    refreshing outcomes/blocks/inlines/HTML/fullsuite into build/comptime-receiver-
    acceptance-017.edn. Previous accepted Learn data predates this edit. No cache
    deletion, no whole Tiger rerun/publication. Preserve userREPLs and preview.
  - Latest focused adapter-context total997/1386,389remaining,140verified IDs;
    not whole Tiger analysis/publication. New13 records: enum2/backing2/layout4/
    authored comptime5. Remaining143identity/128unobserved/106placement/9failed/
    2rejected probes/1root. Saved tiger-adapter-context-backlog-017.edn.
    Private-owner fresh restart exposed namespace-prefix bundle eligibility;
    metadata-based fix3tests/30PASS21.732s,onepack/zero builds/newlibs/exactkeys.
    Comptime2/19PASS plus5actualTiger signatures56.927s, no native preparation
    execution. La Professeure14/131PASS after final formatting. Core adapter
    batch21/248PASS132.396s; post-format native pointer/live-reload4/28PASS25.241s.
    Two-file/two-namespace multiplication/array/get regression checks identical
    native handlers, changed values and zero compilations. Current-core Learn
    refresh50829/exec72470PASS291 comparisons/288transcripts/zero missing outputs;
    stages167181.853/31739.744/656.468/4363.528ms. Full Learn100/12905PASS.
    Actual IAB page reloaded, Hello World Clojure output visible, no native code
    token title tooltips; served/built hashb0d1324d726e6088abf23222d835d1cd437ab5767952caaa99e0c7afe8c62047.
    Owned50829/PID53290 finished/stopped; root65284 remains warm. Browser suite
    exec63024PASS11/11 in27118.656ms; preserve user56037/60588 and preview63979/PID48021.
    Use installed matchingZLS0.17 at stable Library/Caches path documented below.
    Root parity report retains failures and new focused evidence. No cache clear.
  - Final signature/private suites20 distinct tests/153 assertionsPASS; private
    group14/10051.449s, import6/5347.105s. Final Learn291comparisons/288transcripts/
    zero missing outputs,Chrome11/11,servedhash9695d643a48a156f93181c87f33f4223b586d7229f0e90fb4b101868e320415d.
    OwnedLearn50098/PID20220 stopped; root65284/PID52004 warm for Tiger repairs.
    Next remaining adapter defects: `_` enum syntax marker exported as value;
    fromBackingInt builtin doc `BackingInt(T)` emitted as concrete type;
    synthetic constructor field spellings; internal address parameter shadowing.
    Focused984/1386/402 remains distinct from whole analysis/publication/restart.
  - Final signature checkpoint: named Zig `!T` return preserved, dependent source
    literals retain Zig parameter context; concrete reflected aliases unchanged.
    Import group6tests/53assertionsPASS47.105s, both fresh AOTonepack/zero builds/
    no newlibs/exactkeys. ActualTigeru128 shlExact prepares1.068s with native
    invocation blocked. Remaining29adapter replay verifies6more existing fixes;
    latest focused984/1386,402remaining,127verified IDs. Categories143identity/
    128unobserved/106placement/22failed/2probes/1root. Not whole acceptance.
    Root65284 final adjacent/private gate running; final Learn50098/exec55897
    includes last signature changes. Previous49805/PID3083 passed/stopped.
    All evidence in root parity report/current-focused-backlog report.
  - October4 final nested-signature spelling repair: method symbols must stay
    symbols through identifier rendering. Fresh restart exposed10 builds after
    earlier preparation; corrected paths nowzero builds. Stronger missing-probe/
    observation/error gates pass14tests/100assertions in46.483s, including fresh
    preparation and separate runtime JVM. Evidence root private-catalog report.
    Ownedroot65284/PID52004 remains for29 remaining Tiger adapter replays.
    Current-core Learn refresh49805/exec85049 runs withZLS0.17 after final edit;
    previous49396/PID81766 finished/stopped. Prior current-core refresh291outcomes,
    288transcripts/zero missing outputs,Chrome11/11/live DOM+hashPASS. Do not claim
    later source fingerprint until the new refresh finishes. Preserve user56037,
    60588 and preview63979. No unchanged Racing whole-AOT or cache deletion.
  - October4 nested compiler identities: clean refinement recovers100/243;
    actual ordinary preparation100operations/108variantsPASS in93.364s,
    native invocation blocked. Facade replay20operations/14signaturesPASS;
    previous generic dependency closure covers the two missing constants.
    Private computed receivers retain owner scope; field plans canonicalize
    metadata-qualified native identities. Adjacent suite12tests/90assertionsPASS.
    Focused replacements in saved Tiger inventory977/1386,409gaps,not a fresh
    whole-project rerun/publication. Root parity report has six categories and
    concrete examples. Fresh private-member test runs on owned65284/PID52004.
    First Learn refresh291outcomesPASS butHTMLstoppedZLS0.16; owned65401/PID52995
    finished/stopped. Fresh current-core refresh49396/exec94086 explicitly uses
    installedZLS0.17; final acceptance/layout still pending. Preserve user56037,
    60588 and preview63979. Do not redo unchanged Racing wholepreparation.
  - October 4 private static member repair passes 2/12, and actual Tiger
    Headers.jv_blank prepares with native invocation blocked. Invocation and
    preparation share declaring-module scope; private visibility is retained.
    Startup JFR identifies C-binding loading in 542/983 samples; source/API
    fingerprint sharing checks pass 17/195. Lazy batch emission was rejected
    and reverted: 123.118s total. Static emission retained; strict fresh Racing
    checker passes 12 pure calls + 3/16, zero builds/new libraries/missing keys,
    but takes 116.949s. No whole-startup speedup established. Full Tiger inventory
    remains the saved 857/1386 with 529 gaps, not relabeled after focused checks.
    Whole acceptance and final Learn refresh remain pending. Root parity report
    contains the separate evidence; owned Racing 64820 finished/stopped.
    Stronger private-static invocation/restart passes1/3, preserving private
    visibility and zero runtime builds; native host escape/reload tests3/15.
    Final focused groups21 distinct tests/213 assertions PASS. No Learn source
    or upstream output has been rewritten for these runtime/helper changes.
  - Current getter/layout Racing preparation43/43,14434/14434 runtime candidates,
    520/520 functions,zero failures. One3946-handler pack published49.214min,
    genuine fresh strict gate12pure checks+3tests/16assertionsPASS,one packload,
    zero builds/newlibs/unmatched keys/unapproved standalone handlers. Require+
    checks82.155s remains slow; warm12calls24.853ms. Tiger old62544/PID83820
    saved/stopped at4/5,857/1386,529gaps,without bundlepublication; partial
    snapshot tiger-clean-cache-aot-stopped-017.edn. Anonymous argument native
    identity/layout EMPTY/FRESH sync+async1/12PASS, raw/typed changed maps,
    UTF-8/empty slices,zero builds/new libraries. Actual Tiger address parser
    prepares without native invocation; no whole inventory acceptance claimed.
    Adjacent9tests34pass/2cache failures revealed private computed-struct adapter
    mismatch; final10tests/48assertionsPASS with zero failures/errors,including
    fresh-child gates. Root report has evidence.
  - October4 latest: cleanRacingfullrestartFAILED5namespacewrappercompiles/new
    libraries despite12purechecks+3/16PASS. Exactgetter/layoutrootsreproduced;
    EMPTY/FRESHsync+async2/28PASS nowincludesdirectstructconstruction. Newfull
    Racing63797/exec23509preparationrunning,targetracing-getter-layout-aot-017;
    nofreshwholeacceptanceyet. Dependencyfacadelimit512 keepssame32Mcharbound;
    actual165-moduleVSRsecondpassold~1.1s/new<0.1ms,byteidenticalsources;
    4/34cachetestsPASS,notwhole-AOTspeedup. Tigeroldwhole4/5,857/1386candidates,
    529gaps,14prepared/10skipped/1failedfunctions;notaccepted. Corecache report
    hasrawcategories. No unchangedLearnHTML/outcome stages repeated.
  - Latest clean Racing42/43 namespaces,9847/9847candidates,385/385functions,
    zero completed-subset failures;onlysimulation remains. Tigerwhole3/5,8/9,
    VSR/main preparing. Racing's permanent raw-coverage gate1/17PASS rejects
    stale totals,duplicate namespaces,partial handlers and missing scalar
    statuses before runtimewarmup. Fresh restart JVM63366 remains game-unloaded.
    Adapter registration/discovery now read immutable declaration metadata
    without building native-runtime inspection reports;focused3/23 plus guarded
    1/5PASS(overlap,not4unique tests). No type guessing/emission/key change or
    whole-AOT speedup claim. Both live preps keepoldloadedcode. No unchanged
    Learn output/HTML stages repeated. Root cache report/raw evidence.
  - New compiler-only specialization repair uses existing authored closed calls
    and actual Zig observations,no guessed values/types/native body execution.
    Focused Tiger two namespaces3/3operations;fresh ordinary8checks,one packload,
    exactkeys,zero compiles/new libraries/unbundled handlers,1.795s for require+
    calls. Separate source-only wrapper mismatch repaired through existing
    planners:EMPTY-cache fresh9/56 and unit3/15PASS;originalfailure preserved.
    Stronger fresh callable1/8PASS saves actual prepared lookup keys.
    Clean Racing now37/43,8502/8502candidates,338/338functions,zero failures;
    sixpending. WholeTigerold-loaded3/5,8/9stillpendingVSR/main;focusedproof
    doesnotreplacewholeacceptance. No unchanged HTML/outcome stages repeated.
  - October4 user-requested shared-cache reset completed: entire
    `/Users/pfeodrippe/.aguafria/zig` removed/verified absent,53.338GiB reclaimed.
    Reports/toolchains/project builds/user sessions preserved. Historical cache
    proofs remain reports,not currently existing packs. Owned Racing61707 and
    Tiger62395 stopped; partial30/43 and3/5 inventories saved outside cache.
    Fresh normal preparation62545/Racing and62544/Tiger now run public API with
    native invocation blocked,new `*-clean-cache-aot-017.edn` report paths.
    Publication/fresh exact-key acceptance pending. No Learn HTML/outcome stage
    repeated for this deletion-only change. Root cache report records details.
    Latest clean Racing subset20/43,3894/3894 candidates,159/159 functions,zero subset
    failures. Tiger3/5,8/9 candidates;one unobserved comptime-scale multiplication.
    New permanent Tiger coverage gate1/16PASS,recomputes raw records before any
    native calls. Distinct clean restart drivers ready,not warmed/accepted.
    Actual JVM profiling now identified repeated dependency serialization;
    per-refresh key reuse keeps exact results/keys. Focused9/76 and actual
    fresh-child cache3/33PASS. Actual frozen Racing sorting medians63.211->
    18.793ms,21377->4267serializations,not whole-AOT timing. Verification62783
    stopped;active preparations not reloaded. No unchanged Learn HTML stages
    repeated for this identity-preserving runtime improvement. Root cache report.
  - Corrected Racing61707 now24/43 checkpoints,4952/4952 candidates,200/200
    functions,zero subset failures;publication/fresh restart stillpending.
    Permanent project strict cachechecker coveragegate1/11PASS. Additional
    config/report2/12PASS and scalar/callback2/16PASS. Final native type-graph
    regression4/23PASS;actual Tiger probe6.301s,onecompiler observation,no
    semantic/quotaerrors. Intermediate linear-list version31.924s/quotaFAIL
    preserved. Zig type equality remains authoritative,hashcollisions tested.
    OldTiger4/5inventory notacceptedascurrent. Core61626/61918 stopped.
    No Learn outcome/HTML stage repeated;details in root cache report.
    Current cyclicsource serializationreuse4/31PASS,EMPTY/FRESH/invalidation
    3/18PASS,exact old/newhashes. Six alternating syntheticmedians7.977ms->
    2.670ms,not whole-AOTtiming. Core62258 stopped;activejobs not reloaded.
  - Racing strictfresh61501 finished12purechecks+3/16PASS,zero builds/newlibs,
    one packload,but FAILED cache gate:two scalar coercions used standalone
    artifacts. Exactkeys differonly5vs8native link inputs. Generic validated
    scalar constructor profile closure now saved;EMPTY/FRESH regressions4/33
    PASS,one packload/exactkeys/zero builds/unchanged library inventories.
    Corrected wholeprep61707/exec77766 running;newpublication/freshacceptance
    required. No Learn outcome/HTML stages repeated. Owned61501 stopped.
  - October4 goal continuation closed two pending acceptance gates. Current
    Chrome layout suite5/5PASS with bundled Playwright1.62.1/Node24.19.0 via
    NODE_PATH,no installs/expectation edits. All304 displayed regions,640–2600px,
    native-failure output,tabs/live resizing/navigation;served HTTP200 page
    byte-identical to testedHTML. Report.tmp/learn-layout-current-017.edn.
    Tiger actual release C sample/server builds7/7 and6/6safe,aarch64-macos;
    unchanged sample performs818900 transfers/2 accounts,exact balances,deinit
    andexit0. Ownedserver60676stopped;owned1.442GBtestDBremoved,no user data.
    Report.tmp/tiger-release-client-017.edn. No native/body/outcome stages repeated.
    Racing current60329now32/43checkpoints,7868/7868candidates,305/305functions,zero
    completed-subset failures. Final publication/strict restart remains pending.
    Tiger54868confirmed live/changing native compiler child,4/5old-planner
    checkpoints859/1386candidates;notcurrentcoreacceptance. Keeprawfailures.
  - Current ordinary/AOT parity repair: named callbacks retain Zig-confirmed
    declaration identity; imported results and payload paths share producer
    identity; inferred/anonymous return constructors use the actual native type.
    Preparation captures each namespace's load-time compiler configuration,
    including transitive imports,with isolated parallel worker scopes. Full
    bundle/precompile/native-result37 tests/304 assertionsPASS. EMPTY/FRESH
    callback and transitive-config regressions each1/8PASS,one pack,zero builds
    or extra libraries,exact keys. Initial anonymous-reader and Racing restart
    failures preserved in root cache report;no whole-project acceptance claimed.
    Relevant ordinary JVM8/81PASS;core60417 completed/stopped. Formatting and
    diff checks pass. No Learn outcomes/HTML stages repeated for this batch.
    Corrected full Racing60329/PID8637/exec98458 now8/43 checkpoints,184/184
    candidates,11/11 functions,no completed-subset failures. Final publication/
    strict restart pending. Root60307 completed/stopped;user56037/60588 and
    preview63979 preserved. Shared cache not deleted;no Learn source/outcome
    stage changed/repeated. New evidence in CACHE_RUNTIME_AOT_PARITY_2026-10-04.md.
  - Current cache follow-up: compact per-preparation source scan reuse preserves
    keys and validates imports on each request. Actual262644-character source
    20checks638.415ms ->153.040ms,identical facts (not total AOT timing).
    Bundle/precompile28/253PASS plus corrected2/7PASS;original stale-private-
    helper errors retained. New scan concurrency/content/import test11 assertions
    passes. Shared facade cache now bounded LRU with immutable-state checks;
    fresh full runtime59/2334PASS;fresh-child AOT precedence/constructors2/17PASS,
    exact keys/unchanged libraries. Owned root58902 and59077 completed/stopped.
    Racing now33/43 checkpoints8075/8075 candidates323/323 functions,no failures;
    final whole-project reports/fresh checks still pending for all three jobs.
    Both normal0.17 game standalone builds succeed,not new app launch claims.
  - Final cache-core Learn refresh completed:286 upstream outcomes plus5 reviewed
    special cases;14 translated blocks/7 Zig-only;355 authored inline checks.
    First owned59238 correctly rejected systemZLS0.16 at HTML. Only HTML/tests
    resumed in fresh owned59421 with documented AGUAFRIA_ZLS matching0.17.
    HTML9156ms:291 file examples,288 REPL transcripts,zero missing output panels.
    Five Clojure suites100 tests/12905 assertions PASS. Highlighter6/6PASS.
    Separate layout test could not load its Playwright dependency;not a green
    layout run. Existing IAB page reloaded and DOM checked:hello_again output,
    actual ZLS *const i32 hint,and zero typed-span browser titles. Temporary
    CUA viewport enabled rendered checks at1600/1194/900/640px: equal columns,
    aligned Shell/REPL panels,no horizontal overflow,working source tabs.
    Actual1600px screenshot visually inspected;viewport reset afterward.
    This does not relabel the Node dependency failure as a passing suite. Evidence
    build/refresh-cache-final-017.edn and refresh-cache-final-html-retry-017.edn;
    original ZLS failure retained. Both owned refresh JVMs stopped;user56037
    listener confirmed and .nrepl-port restored. Live game60588 and preview63979
    preserved. No successful outcome stage repeated on retry.
  - Default dependency mismatch fixed in LaProf/Racing/HTTP:published0.1.7 is
    Zig0.16 and lacks the precompile API. All now use the same local core by
    default,no override aliases. Actual CLI classpaths checked;new regression
    15 assertionsPASS. LaProf closure then failed at assembly:790+130 artifacts
    differ only by ordered appends of two Studio link dependencies. Single-pack
    assembly now uses the full ordered link list while preserving every input
    artifact key. Divergent compiler settings/link lists still reject. Real
    external translated-C7 assertions and negative4 assertionsPASS;new empty-
    cache/fresh child1/8PASS:one pack,zero builds/new libraries,exact keys.
    Root58572/PID27766/exec98721 broader bundle/precompile28/242PASS;additional
    bundle-failure report test1/7PASS,retains completed coverage/diagnostics,
    marks bundle failed and still throws. Together with default-dependency1/15,
    30 tests/264 assertions across three recorded runs. Core verification
    JVM exited0;PID/listener gone. Formatting/diff checks pass.
    LaProf57606/PID90967 retry uses saved bundle code in preparation-only JVM;
    normal fresh game checker still required. Current220 modules/95 coercions.
    Racing whole current-source AOT58479/PID25852/exec17100 runs two bounded
    workers,327 modules last snapshot,native invocation blocked. Driver.tmp/
    racing-replay-fixed-aot-017.clj;newreport racing-replay-fixed-aot-017.edn.
    Tiger54868 still running. Saved bundle namespace normally reloaded into both
    owned preparation JVMs before final assembly;this is not fresh acceptance.
    Preserve user56037/60588 and preview63979.
  - Current continuation: Racing replay blockers repaired and verified5/36.
    Native1200-tick replay captures599 and matches fingerprints exactly;
    v2 fixture regenerated through strict parity gate for20 racers/10 teams.
    Root PROJECT_NREPL_VERIFICATION_2026-10-03.md and Racing ledger hold evidence.
    Completed owned Racing58180/PID16489/exec4500 shut down world/exited0.
    Old Racing AOT snapshot is stale after source/array-type changes; not new
    whole-project acceptance. Same LaProf constructor prep57606/PID90967 now
    213 registered modules/101 coercions after51min,still preparing/not published.
    Tiger54868/PID55935 has4/5 checkpoint files and is in main probe isolation;
    still running,not finished. No core cache-key/lookup change this turn.
  - Current ordinary/AOT game acceptance checker now rejects generated handler
    disk hits outside the packs (except the reports' explicit standalone
    exclusions), so a previously compiled library cannot mask an AOT gap.
    Exclusions now preserve exact artifact IDs,not entire namespaces. Checker
    regression1/3PASS and core standalone-report identity1/3PASS. Evidence
    .tmp/cache-exclusion-identity-017.edn. Owned57996/PID12486/exec27731 exited0.
    Saved updated finish! definition installed in preparation-only57606 so its
    final report retains exact exclusion IDs. Artifact keys/Zig output/lookup
    unchanged. Same owned57606/PID90967/exec84996 has
    reached Studio's native inspection after the five callable namespaces;
    no terminal report yet. Tiger54868/PID55935/exec56721 remains in compiler
    probe isolation. Neither job was restarted or called finished from CPU0.
    Keep user56037/60588 and preview; fresh game restart driver now includes
    :unbundled-handlers in its summary. Formatting/diff checks pass.
  - October4 whole-project reports have finished, but their semantic planners
    predate the current shared-planner/type-query fixes. LaProf6/6 namespaces,
    8165/8329 runtime candidates,375/376 functions (one process entry),one1662-
    handler pack:17,911,712 code bytes/31,393,701 debug bytes,130.234min. Racing
    43 selected/28 analyzed/15 host-only namespaces,14179/14432 candidates,
    520/520 functions,one2231-handler pack:21,538,464 code/35,933,488 debug bytes,
    68.932min. Both have zero baseline failures; neither is current exhaustive
    ordinary-JVM acceptance. Finished54869/PID55967 and56197/PID62484 stopped.
    Fresh ordinary LaProf57456/PID88418 then passed3 tests/51 assertions and
    reused30 bundle entries/one load, but compiled two coercions absent from
    the pack:typed string construction and direct mutable undefined storage.
    This is a preparation-closure gap, NOT duplication of existing AOT keys.
    Every actual hit matched its manifest. Evidence game-shared-planner-restart-
    017{,-events}.edn. Completed verification57456 stopped.
    New generic closure prepares declared operand/result constructors and the
    primitive literal's owned-storage variant. EMPTY-cache/FRESH child regression
    1/8 PASS:one pack,zero compilations/extra libraries,exact keys match.
    Full bundle/precompile25/228 completed227PASS/one stale assertion that counted
    unloaded namespaces as loaded bindings;corrected snapshot keeps only actual
    bindings and requires a nonempty baseline. Changed test1/6PASS;unchanged
    other24 tests/223 assertionsPASS. Owned57568/PID89068/exec35125 exited0.
    Current LaProf follow-up57606/PID90967/exec84996 runs5 namespace callable
    preparations plus Studio analysis (the affected namespace),native invocation
    blocked. Its new constructor initially lost the namespace of FrameBuilder
    (defconst function-pointer type alias). Fixed with existing qualify-native-
    type helper,retaining declared identity,no type inference. Strengthened
    alias EMPTY-cache/FRESH regression57736/PID764/exec31403 now1/8PASS:exact
    keys,one pack,zero builds/extra images;completed owned JVM exited0.
    Final fixture also covers mutable bool storage (same scalar storage closure).
    Fresh empty-cache/restart57818/PID10294/exec25466 again1/8PASS,zero builds/
    extra images,matching manifest identities;owned JVM stopped. Final result
    .tmp/cache-constructor-final-regression-017.edn. Formatting/diff checks pass.
    LaProf follow-up retried in same
    preparation-only JVM using the saved updated precompile-function! definition;
    normal acceptance must still be fresh. Driver
    .tmp/game-constructor-closure-preparation-017.clj,report
    .aguafria/precompile/game-constructor-closure-017.edn. No app/cache deletion.
    After it publishes:stop only owned57606/start fresh ordinary game nREPL with
    the same dev/tools/test classpath;load .tmp/game-constructor-closure-restart-
    017.clj. Permanent game_cache_check verifies3/51 tests,all actual keys,zero
    compilations/new requested libraries,ordinary requires;it saves failure
    details and distinguishes unrelated concurrent writes. Do not claim this
    fresh game acceptance before actually running it.
    Tiger54868/PID55935 remains in actual compiler isolation,not idle. VSR's
    SyncTarget refers to missing sync.Target in origin/default too; no blind
    replacement with CheckpointState or silent export removal has been made.
  - October4 ordinary game cache follow-up: fresh nREPL57106 used current shared
    planners against recording-tool's existing250-handler AOT. Four observed
    type queries and hello/jello/new UTF-8 hash calls pass:7 bundle hits,one
    bundle load,9 memory hits,one namespace-image disk hit,ZERO compilations
    and new libraries for requested keys. Exact entry identities match the
    preparation manifest. New recording_cache_check.clj separates unrelated
    concurrent cache writes (five observed) and bounds source-cache scanning.
    The24.354s recorded duration includes inventory work, NOT native-call time.
    Completed nREPL57106 stopped. Whole-project AOT remains incomplete.
    Package catalog default moved into ignored generated output; stale tracked
    HTTP0.16 catalog removed recoverably. Actual HTTPprepare now0.17,6 namespaces/
    22 members. Container walker recognizes compound-call heads without treating
    them as named declarations. Package suite7/52 and fresh HTTP normal-load/live
    listener1/19 PASS. Completed root56942 and HTTP57124 stopped. Learn allocator
    mapping now points at actual0.17 SafeAllocator without altering upstream HTML;
    Learn refresh now completes291 outcomes and288 transcripts/zero missing
    panels. Original full100-test/12905-assertion run had one failure in the new
    test:it wrongly expected Zig's deprecated DebugAllocator Var to be absent.
    Corrected only that assertion to actual SafeAllocator container metadata;
    changed test1/8 PASS,other99 tests were already passing and not rerun.
    Browser/highlighter11/11 PASS. Served IAB DOM shows both new allocator notes
    and actualHello,World! output;screenshot blocked by zero-width browser pane,
    NOT claimed as visual inspection. Official0.17 ZLS used. Completed Learn57130
    (exec25013) stopped. Driver.tmp/learn-cache-catalog-refresh-017.clj; keep the
    original failed-suite result plus correction/acceptance evidence in
    build/refresh-shared-cache-catalog-017{,-correction,-acceptance}.edn.
    Long-job checkpoint snapshots: Tiger4/5,859/1386 candidates; LaProf5/6,
    3973/4100; Racing29/43,7311/7446. Their semantic planners predate the latest
    fixes; these are progress counts, NOT current whole-project acceptance.
  - October4 shared-cache planner follow-up: callable expressions and compound
    assignment registration now shared by ordinary/precompile paths. Actual
    artifact keys/bundle IDs reported. Full bundle/precompile24/220 and targeted
    JVM8/59 PASS; formatting/diff checks pass. Values checker removed source-only
    require bypass and now includes normal startup, native full-output comparison
    and library inventories. EMPTY temp-cache preparation28/28 operations,1/1
    functions,one68-handler bundle in32.247s. FRESH real nREPL startup+five bodies
    +native comparison2733.364ms:34 bundle hits,one load,14 memory hits,two prepared
    namespace-image disk hits,ZERO compilations/new dylibs; every requested key
    matches preparation. Preparation56800/PID30509 and restart56839 stopped.
    Exact report CACHE_RUNTIME_AOT_PARITY_2026-10-04.md. Whole-project AOT still
    incomplete; no user cache/app changes and no HTML-facing code change.
  - October4 type-query cache follow-up: discovery now observes actual Zig
    types for a/type; preparation and ordinary calls share the explicit type
    parameter planner. Observation1/5 and initial EMPTY-cache/FRESH ordinary
    AOT restart1/7 PASS: one pack loaded once, zero new compilations, identical
    libraries, invocation blocked during preparation. Full bundle/precompile
    23/209 PASS; three nearby JVM type/value tests pass. Discovery7 tests pass;
    eighth stale operation-name expectation corrected to existing emitter
    normalization, strengthened deref test1/13 PASS. Formatting passes.
    Completed root56268/PID67488/exec68003 exited0. Focused current-core
    recording-tool AOT COMPLETE552/552 candidates,12/13 functions (process
    entry skipped),all17 type queries prepared in88.012s. One250-handler pack,
    no standalone handlers,1,668,160 code/3,704,218 debug bytes. Fresh ordinary
    nREPL56621 requires normally, evaluates4 distinct observed type signatures,
    hashes hello/jello correctly: ZERO compilations,7 bundle hits,one bundle
    load,one namespace-image disk hit,3422.559ms including startup. Reports
    recording-type-current-017.edn/recording-type-restart-current-017.edn.
    Both owned56574/PID19310/exec60479 and56621/PID24389/exec95157 exited0.
    No game/editor/GPU rerun or cache deletion. This one namespace is accepted,
    not the full6-namespace game/studio. Deferred9 casts need outer context;
    Node is1 type declaration, neither claimed as a callable.
    Per-graph cyclic fingerprint reuse passes full runtime58/2321; synthetic
    80-cycle/300-shared-value timing46.407292ms ->21.928333ms, identical hashes;
    NOT whole-project AOT speedup. Only graph-work defn installed in owned jobs.
    Tiger54868/PID55935 now4/5 checkpoints,15/25 functions,859/1386 candidates,
    VSR baseline failure and52 failed handler records/47 operations remain.
    LaProf54869/PID55967 is1/6,535/552 candidates. Racing56197/PID62484 is17/43,
    150/150 functions,3692/3799 candidates. Semantic snapshots predate new fix;
    do not count them as current whole-project acceptance. Details/categories
    in PROJECT_NREPL_VERIFICATION_2026-10-03.md. No user apps/caches touched.
  - October4 project follow-up: Racing cast/cache regressions2/11 PASS,
    compiler-only1/1 functions and5/5 candidates prepared, no invocation.
    Core C persistence suite8/71 PASS; actual generated-catalog serialization
    28.98ms compact vs5575.97ms pretty (same parsed data, not whole startup).
    HTTP0.17 standalone builds6405ms and serves200/body/UUIDv4; stopped afterward.
    Racing portable replay still fails3 assertions: stale version1/zero-roster
    fixture; real native1200-tick parity saturates512 storage for20 racers.
    Guards/fixture untouched; owned world shut down. Full current-core Tiger
    and LaProf AOT/restart remain pending; do not relabel historical snapshots.
  - October 4 planning allocation follow-up: direct import traversal preserves
    generated Zig/artifact keys; emitter 72/432 PASS. Derived linkage plans are
    bounded 64 entries/32Mi source chars; runtime/preparation 67/2369 PASS.
    Bundle source streaming keeps paths/fingerprints, validates before linking;
    full bundle 11/136 PASS. Root 54934/PID62718 remains active for std.c alias
    regressions. Current Tiger 54868/PID55935 AOT 3/5 checkpoints and LaProf
    54869/PID55967 AOT 1/6 remain running. Historical Racing 52294 has 42/43
    checkpoints, 385 functions, 9605/9653 candidates; not current acceptance.
    clock_gettime/nanosleep alias planning is compiler-reflected. Fresh empty
    preparation/ordinary JVM pair 2/18 PASS: no preparation invocation, zero
    restart builds and identical native-library inventories. Declared generic
    return identities are checked by Zig before retention; imported type readers
    use the ordinary preparation path. The in-flight project AOTs predate that
    planner change and need current-core follow-up. Additional facade-cache
    source bound is implemented but its new targeted/native proof is pending.
    Latest continuation: facade bound validated14 assertions, runtime/preparation
    68/2383 PASS and installed in active Tiger/LaProf JVMs; disk/artifact keys
    unchanged. Whole JVM suite passes81/761 in four bounded process batches;
    combined JVM hit macOS DYLD's TLS-key image limit and is not a pass. One
    initial shell-interception assertion passes on targeted25-test rerun;
    preserved as non-reproduced, not claimed fixed. Current bundle11/136 and
    alias2/18 rechecks pass. Broad16-namespace empty-cache restart1/5 PASS:
    483 preparation builds/zero callable images; ZERO restart builds and
    identical103-library inventories. Current core matrix163/3303 across
    bounded runs, not one monolithic JVM. Completed root55569/PID11402/exec37339
    is stopped; root54934/PID62718 exitedSIGABRT.
    Historical Racing ended43 checkpoints,520functions,14130/14180 candidates;
    no final bundle due interrupted parent. Owned idle52294/PID81027 stopped.
    Detailed ledger:
    PROJECT_NREPL_VERIFICATION_2026-10-03.md. Do not repeat completed HTML work
    for identical-output performance edits; no output regeneration this phase.
  - October 4 nested-field/cache parity follow-up: compiler probe traverses
    actual Zig field/pointer/function types with cycle detection, preserving
    nested nominal identities. Borrowed fields retain their receiver's native
    identity, address, mutability and lifetime. Adapter writer arguments are
    qualified before hashing, and redundant explicit type wrappers in receiver
    schemas are normalized. This fixes six verified preparation/runtime misses:
    Exchange/result, Box/value for i32/u16, Counter/increment/plus and
    SemanticVersion/major. Latest EMPTY-cache preparation/FRESH ordinary JVM
    restart covers 16 namespaces: 1 test/5 assertions PASS, 479 preparation
    builds, zero native invocations/loaded functions during preparation, ZERO
    restart builds, identical nonempty 103-library inventories. Direct
    identity regression1/2, nested field/probe2/13, field/private/constructor
    regressions8/48 and full runtime55/2,291 pass. Preparation and ordinary
    invocation use the same adapter planner and bundle-first artifact lookup.
    Evidence: Java temp logs aguafria-discovery-13706455395493851647.log
    (prepare), aguafria-discovery-5346222559427439304.log (restart), cache
    .aguafria/precompile-tests/discovery-9092479791512895335.
    Separate std.testing allocator resource now explicitly initializes Zig's
    test context before exposing its native exports; synchronous and
    cross-thread allocator regression plus bound methods2/14 PASS. Native
    deftest execution still uses its own runner and leak checks.
    Fresh Learn54471 completed outcomes, blocks, inline evidence and HTML
    before loading test suites:291/291 outcomes,288 transcripts, zero missing
    output panels; combined suites99/12,897 and browser/highlighter11/11 PASS.
    Actual IAB preview reloaded and visually checked; x and x-ptr tooltips use
    ZLS generated-Zig i32/*const i32 results, no native-title duplication.
    Earlier four capture failures were from
    tests loading lesson namespaces before regeneration, not lesson failures;
    the guard is preserved. Current report build/refresh-zig-017-final.edn.
    Root53916/PID81164 and Racing monitor53639/PID16717 exited after completed
    verification. Learn54471/PID31540 also exited; independent preview/user
    sessions are preserved. Racing52294 whole AOT still runs an earlier core
    snapshot:39 checkpoints,341 functions,8,598/8,643 candidates. Do not treat
    its historical gaps as latest failures.
  - Additional cache parity gaps fixed October 4: composed comptime string
    results retain both compiler source and native pointer ownership; ordinary
    invocation and preparation select the same result writer. Inspection now
    resolves normalized vector/array constructors through native syntax before
    clojure.core. Argument types still come from Zig. Broad empty-cache/fresh
    ordinary JVM check passes 1/5 across 15 namespaces: ZERO builds and identical
    nonempty entire native-library inventories. Native storage checks3/21 and
    focused operator/concat checks4/33 pass. Emitter71/422 passes.
    Converted source loading now uses registered definitions, not a nullable
    emitted-source cache, to decide whether to load a module. Adapter
    registration therefore cannot trigger source replacement and delete its
    cleanup functions. Unit1/7, runtime55/2,291 and converted-native4/23 pass;
    first-cold Tiger composed-format check6/6 prepares without invocation.
    Logs: /tmp/aguafria-fresh-discovery-cache-parity-fixed-017.log,
    /tmp/aguafria-converted-source-cache-runtime-suite-017.log,
    /tmp/aguafria-native-converted-cache-full-suite-017.log,
    /tmp/aguafria-tiger-composed-first-cold-fixed-017.log.
    Current-core Learn refresh PASSES: 291/291 outcomes, 288 transcripts,
    zero missing output panels; combined reference/fidelity/direct-JVM/audit
    suites99/12,897 and browser/layout/highlighter11/11 pass. Actual IAB preview
    reloaded and visually checked. Fresh Learn53189/PID94228/client83443
    finished and its owned JVM is stopped. Evidence:
    build/refresh-handler-cache-parity-017.edn,
    /tmp/aguafria-learn-current-handler-cache-refresh-017.log and
    /tmp/aguafria-learn-current-handler-cache-browser-tests-017.log.
    Tiger53102/PID77899,53171/PID93993 and root52995/PID58827 are stopped.
    User sessions and preview remain untouched.
  - Current cache continuation (October 4): ordinary arithmetic discovery now
    records the operator selected by emission, including raw a/op and mod;
    argument types remain Zig compiler observations. Latest emitter suite
    70/416 passes with unchanged emitted source. Operator + valid tuple focused
    checks pass; the fresh ordinary restart found five native test-definition
    check libraries not warmed by AOT. Repair is complete: precompile now
    uses the ordinary check path at its registration point, never executing
    tests, preserves per-image :test-checks and reports failures separately.
    Broad restart/operator pair passes 2/17 with zero builds and identical
    entire library inventories. Preparation's in-process subset passes 10/48;
    fresh sync+async restart passes 1/18. Earlier wrapped-diagnostic assertions
    are fixed and superseded by /tmp/aguafria-precompile-final-in-process.log.
    Owned root52247/PID67937 stopped. A redundant metadata state lookup now
    skips non-vars and computes var references once: runtime54/2,284 passes;
    synthetic 300 lookups384.79ms ->0.053ms (not whole-project timing).
    Fresh full preparation recheck passes 11/66, zero failures/errors;
    ordinary sync+async restarts compile zero libraries and retain identical
    inventories. Owned52616/PID39966 stopped after verification;
    /tmp/aguafria-cache-parity-final-017.log.
    Full Racing AOT owned52294/PID81027/client43147 runs 43 selected
    namespaces from 44 source files, two workers, shaders explicitly ignored.
    Latest 22 checkpoints: 4,579/4,616 candidates, 192 prepared functions, eight
    host-only namespaces incorrectly reported analysis-failed (now fixed in
    source) and two load failures from dataset's eager native read and its
    downstream jev-evaluation require. Both now load source-only, dataset1/4
    passes, but the old checkpoints have not been replaced. No final bundle/count
    yet. The 37 incomplete candidates comprise one old vector shift failure
    (u8 shift-count vector, fixed to Zig's u3 in current source; focused native
    regression2/13 passes) and 36 non-runtime/nominal operand cases such as
    monitor's (field ar actor) and (field br actor). Separately, 210 standalone
    calls need result context, e.g. (k/intCast count)/(k/intCast length), and
    22 type declarations are non-call operations (Decoder/Span in inference).
    Current report:
    examples/racing-game/.aguafria/precompile/racing-game-017-full.edn(.d),
    /tmp/aguafria-racing-full-aot-017.log. Running JVMs have not reloaded the
    host-only classification/metadata changes. No graphical/game/worker bodies run.
  - Startup cache parity is now COMPLETE for the checked workloads, superseding
    the initial-image gap below. Initial namespace images are compiled without
    publication; their snapshots live only for the preparation run. Runtime and
    AOT share callable source generation and artifact lookup. Compile-only mode
    never skips persistence because a handler is loaded in memory.
    Empty cache /tmp/aguafria-tiger-startup-reuse-pnVG3b: 81.00s preparation,
    4/4 callable operations +1 function; two initial images prepared/seven lazy
    converted images skipped. One nine-handler bundle (111,616 code bytes,
    128,030 debug bytes), two explicit standalone relative/dynamic-asset handlers.
    Fresh ordinary startup returns 48/48, 5 bundle hits/4 disk hits, ZERO new
    libraries across the entire inventory, including startup. Owned Tiger
    preparation51805/PID48305 and invocation51883 stopped. Logs:
    /tmp/aguafria-tiger-startup-{preparation,invocation}.log.
    Strengthened ordinary-require restart test covers sync+async (1/14); full
    precompile suite 9/53 passes. Four native reload/migration checks 4/28 pass.
    Final explicit-signature check 1/3 passes after fixing the loaded-memory
    bypass; earlier failures in the focused log are superseded. Runtime 53/2,271
    and bundle 1/17 pass. Racing foundation finished in 237.26s: 6/6 namespaces,
    44/44 functions, 574/574 runtime candidates (594 inventory: ten context
    calls, nine type declarations, one directive), zero baseline failures. One
    209-handler bundle: 1,325,568 code bytes/2,666,570 debug bytes. Seven initial
    images prepared; converted Box3D lazy. Fresh ordinary 52139 returns
    [36.0 0.5 1.0] in 17.96s, 4 bundle hits/3 disk hits, ZERO compilations or new
    libraries including startup. Generated prefixes now cannot incorrectly
    complete async file loading. Catalog name bookkeeping includes unions;
    project4/8 passes. Incomplete circuit hash moved recoverably to
    /tmp/aguafria-startup-miss-recheck-AzNyoH. Preparation51933 and
    restart52053/52139 stopped. Logs /tmp/aguafria-racing-foundation-aot.log and
    /tmp/aguafria-racing-foundation-restart-fixed.log. Preloaded dependency
    images now follow native snapshot modules; focused regression passes.
    Final combined prep/project suite 13/61 passes, zero failures/errors:
    /tmp/aguafria-cache-final-restart-tests.log. Owned root 51778 stopped after
    verification; user CIDER 56037/PID8855, live game 60588/PID19986 and preview
    63979/PID48021 remain preserved.
    Whole Racing AOT and ordinary-core-operator discovery remain incomplete;
    the extra k/+ f32 signature was not in the prepared inventory. No
    game/worker bodies are executed by preparation.
  - Cache parity repair now retains refreshed source-only type identities
    during AOT, without touching published generations. Unit/native checks:
    runtime 53/2,271, four publication/reload/migration tests 4/28, fresh bundle
    restart/miss test 1/17. Clean cache /tmp/aguafria-tiger-aot-reuse-IHMss8:
    preparation51423 took 78.73s, 4/4 callable operations +1 function. One pack
    of nine handlers; two relative/dynamic-asset handlers remain standalone.
    Fresh ordinary51492 returns 48/48 with 5 bundle hits/4 disk hits and ZERO
    new helper dylibs. One initial namespace-load image (not in targeted AOT)
    is new; do not claim zero whole-startup compilation. This async image is
    absent from expression-local event counts. Logs /tmp/aguafria-queue-clean-
    {preparation,invocation}.log. Preparation51423, invocation51492, trace
    JVMs51307/51313 and root50849 all stopped after verification.
  - Post-cache-parity Learn refresh is COMPLETE: 291/291 outcomes (286 native
    comparisons, five reviewed), 288 transcripts and no missing output panels.
    Reference suite 79/11,616 and layout/highlighter checks 11/11 pass.
    Actual preview reloaded and visually
    checked: both source/output columns visible, Hello Again uses a/defn.
    Evidence: build/refresh-after-cache-parity-017.edn,
    /tmp/aguafria-learn-after-cache-parity.log, and
    /tmp/aguafria-learn-cache-parity-html-tests.log. Learn51460 and51560 stopped;
    user CIDER8855, live game19986 and preview48021 are preserved.
    Start Learn rendering with AGUAFRIA_ZLS set to
    /Users/pfeodrippe/Library/Caches/aguafria/zls/eab2be0fd74443a27662da809b771c4ad0d2afcf/bin/zls;
    system ZLS0.16 is correctly rejected. Outcomes were retained on HTML retry.
  - Cache-order follow-up: dependency facades now ignore owner compilation
    history; queued publication loads converted dependencies before planning.
    Fresh preparation50975 and ordinary invocation51001 now agree on the
    queue-size adapter key (`ef422fe3...`); both calls return 48. Overall events
    improved from three compilations to one, plus five bundle hits/three disk
    hits. Two observed artifact variants remain outside this targeted AOT
    inventory, so do NOT claim complete zero-compilation reuse.
    Logs /tmp/aguafria-queue-key-{preparation,invocation}-fixed.log.
    Owned Tiger50681,50707,50867,50975,51001 were stopped after inspection.
  - Runtime 52/2,264; retained-generation regression 1/3; four relevant native
    publication/reload/migration regressions 4/28, all pass. Logs:
    /tmp/aguafria-runtime-cache-order-tests.log,
    /tmp/aguafria-retained-generation-cache-fix.log,
    /tmp/aguafria-aot-cache-hot-reload-regressions.log.
  - La Professeure's old AOT client timed out after an hour while workers
    continued. Owned PID51631 was stopped; the two completed checkpoints below
    are preserved. It is NOT still running. Bounded GPU profile finished in
    owned nREPL51059/PID28036 (now stopped). Script/log:
    /tmp/aguafria-game-aot-profile.clj/.log. Source loading alone took 205.17s;
    observed 1,128 operations by 214.69s. At load completion: 96 reference
    refreshes (22.16s total), 254,426 source-fingerprint calls. The sample then
    prepared 35 functions and 26 handlers from its first 30 operations, ending
    at 357.61s; not a full AOT run. Total 205 refreshes/41.43s and 266,789
    source-fingerprint calls.
  - Found quadratic qualified-name scope rebuilding during generated binding
    validation. Replaced it with direct namespace-scope membership checks,
    preserving lexical scope and unknown-name rejection. Emitter 69/400 and
    runtime 52/2,264 pass. Synthetic 200 declarations × 10,000 known names:
    273.81ms before, 0.415ms after (NOT end-to-end AOT speedup).
    Fresh GPU source load: 126.69s/88 declarations (~38% lower), owned
    nREPL51158/PID28973 now stopped. Log:
    /tmp/aguafria-game-aot-load-fixed.log. Root tests50849/PID25775.
    These later core edits postdate the Learn refresh recorded below.
  - Empty-cache/fresh-JVM bundle regression: 1 test/17 assertions pass; now
    checks no standalone dylib after runtime hits, not only after preparation.
    /tmp/aguafria-aot-no-duplicate-regression.log. Tiger's remaining sizeOf
    adapter mismatch was NOT covered by that scalar fixture; resolved by the
    source-only identity fix above. Root recursive
    fixture same-JVM check returned 16/16 with bundle hits and no compilation.
  - Post-core Learn regeneration completed: 291/291 outcomes (286 native
    comparisons, five reviewed cases), 288 REPL transcripts, no missing output
    panels. `build/refresh-after-aot-core-017.edn` and
    /tmp/aguafria-learn-after-aot-core.log contain the evidence.
  - Reference suite: 79 tests / 11,616 assertions, no failures/errors. Browser
    layout/highlighting: 11/11. Reloaded the actual preview and visually checked
    both Hello World side-by-side source/output pairs; REPL output is visible.
    Log /tmp/aguafria-learn-post-aot-reference-tests.log. Finished owned Learn
    nREPL50446/PID6492 stopped; preview63979/PID48021 remains available.
  - Tiger logging repair: 71/77 expanded handler signatures prepare. Remaining
    six are three composed format strings (`k/++`) with two tuple forms each.
    Do not count the earlier diagnostic harness's unexpanded representations.
  - Fresh Tiger queue returns 48 from both function and equivalent REPL
    expression, but initially had three compilations, five bundle hits and one
    disk hit. Zero-compilation assertion failed: reuse is NOT verified yet.
    Owned nREPL50470/PID8292 stopped. Focused key investigation now compares
    compiler inputs from preparation50681/server6305 with invocation50707/
    server71505. Temp scripts and logs: /tmp/aguafria-queue-key-{preparation,
    invocation}.clj/.log; captured key inputs are separate EDN files there.
    Preparation alone: 60.72s, 4/4 runtime operations, 1/1 function; no native
    calls. It still reproduces the old prepared artifact key, so this is not
    simply an intervening emitter edit.
  - Root runtime tests: 50/2,257; focused discovery: 4/27, no failures/errors.
    Completed root50098/PID84099 and Tiger50244/PID95987 are stopped.
  - Historical La Professeure AOT51631 was stopped after timeout; only two
    namespace checkpoints are current. Thread dump:
    /tmp/aguafria-game-aot-threads-20261003.json. Do not count Sep30 checkpoints
    as current or describe this stopped run as active.

- Current AOT follow-up (after headless Ghostty completion):
  - Tiger project now has an explicit five-namespace `:precompile` alias for
    the three reload fixtures, VSR and main, not all 245 generated namespaces.
    Authored `ak`/raw comptime attributes migrated to `k`/`:attrs`; benchmark
    matchers compare forms structurally. QueueItem uses public
    `QueueType(QueueItem).Link`, not private QueueLink. Benchmark edits still
    need their focused medium/complex verification.
  - First Tiger run: 127.09s, 19 prepared / 12 skipped / 4 failed functions;
    2/2,026 operations prepared, three failed baselines. Bundle: 26 handlers,
    206,400 code bytes + 339,947 debug bytes. This is superseded only where
    newer per-namespace checkpoints explicitly exist.
  - Fixed inspection's cyclic root identity using Zig's actual logical root
    module name. JVM native-result wrappers now reflect the function's return
    type instead of reconstructing anonymous/alias types. Generated optional,
    slice and error-union helper locals use reserved internal names, avoiding
    user `storage`/`ErrorUnion`/`name`/`items` collisions.
  - Native result readers preserve compiler return-type identity; preparation
    and invocation qualify the reader type identically before cache lookup.
    New regressions: 2 tests / 15 assertions pass, including compile-only
    preparation and zero compilations on first invocation/decoding. Runtime
    50/2,257 passes (one source-string expectation updated for renamed locals).
    Nested-value 1/2 and separate-JVM error-union cache 1/6 pass. Distinct total
    across full/focused runs: 54 tests / 2,280 assertions, zero remaining test
    failures. Logs /tmp/aguafria-{result-cache-final,result-type-and-runtime,
    result-cache-and-nested}.log; the last contains the intermediate cache-test
    failure fixed by the focused final run.
  - Tiger retry FINISHED in 1,199.36s: 688/2,028 operations fully prepared;
    21 functions prepared / 12 skipped / 2 failed. 1,007 handler records
    prepared / 147 failed / 139 unsupported / 11 deferred / 1 partial. One
    baseline failure, 17 rejected probes, 970 unobserved operations. This run
    loaded core before the subsequent fixes; do not rerun it wholesale yet.
    Log /tmp/aguafria-tiger-precompile-repaired-017.log. Completed owned
    nREPL49812/PID51203 was stopped. Replacement nREPL50244/server82400 is
    using current core for targeted follow-up.
  - Root-slice diagnosis corrected: NOT an alias-regex failure. A retained root
    adds dependencies that refer back to more root members. Fixed-point closure
    now handles that. parse_addresses and root_members both prepare; reduced
    native fixture passes 1/2. Result-type suite now 3/17. Focused discovery
    group 5/43 and emitter suite 68/397 pass.
  - Lazy invalid roots are isolated with compile-only bisection. Original
    baseline diagnostics are retained; independent roots must compile before
    their probes are used. VSR's only rejected root is SyncTarget (17 attempts,
    original baseline1, remaining-root baseline0). origin/main itself names
    missing vsr/sync.zig.Target; no vendor deletion. Native regression 1/7.
  - Recursive field probes now run in their container's comptime block after
    field resolution. Observed external type expressions are qualified and
    their actual imports emitted. Tiger queue now has 4 observed/prepared
    operations, one non-call struct declaration, 1/1 function prepared, no
    rejected probes. Imported recursive-type regression 1/4 passes. Broader
    placement group 6/58 passes (before the extra external-identity import fix).
  - Member preparation now preserves source string arguments as the ordinary
    JVM member planner does. Native regression 1/6 includes zero compilation
    on the first call after preparation. Recheck affected Tiger log operations;
    the old full report has 77 comptime-evaluation handler failures in main.
  - Historical La Professeure AOT (timed out; workers subsequently stopped)
    ran in nREPL49827/PID51631, server24266,
    client56046, log /tmp/aguafria-professeure-precompile-017.log. Six namespaces
    from existing :precompile alias; no native bodies run. recording-tool
    checkpoint: baseline0, 12 functions prepared/1 skipped,
    544/545 observed operations, 535 fully prepared, 9 deferred, 1 non-call.
    Scene checkpoint is also current: 40/40 functions, 976/1,056 operations
    prepared; 28 deferred, 49 unobserved, 3 unsupported, no failed handlers.
    Other four checkpoint files are Sep30 and MUST NOT be counted as current.
    17 authored C callbacks migrated from raw qualifiers to `:callconv :.c`
    before this run. It does not include the later core fixes in its loaded JVM.
  - Finished root test nREPL49869/PID52343 (server31213) was stopped after its
    checks. Current root regression nREPL50098/PID84099/server80517 is active.
    Preserve live game60588/PID19986,
    user CIDER56037/PID8855 and preview63979/PID48021. No Ghostty GUI work.
  - Next: finish/classify these AOT runs, fix remaining root-slice/probe cases,
    focused Tiger reload tests, fresh-JVM reuse, Racing AOT and post-core Learn
    HTML/regressions. Also audit anonymous-return round trips: decoding is now
    compiler-anchored, but value/qualified-type still has a separate recursive
    symbol-qualification path for ABI comparisons.

- Scope correction: user explicitly waived Ghostty GUI verification. Continue
  native/JVM reload and AOT, not GUI work. Xcode is NOT an established blocker:
  existing compiler guards cover glass APIs. SwiftPM hung downloading Sparkle
  2.9.6; its official zip was downloaded and SHA-256 verified against its
  manifest, then placed in SwiftPM's artifact cache. The next app build reached
  compilation. Five new Swift trailing commas in three files were removed;
  those files parse successfully. No app was launched, no Xcode process remains.
  Do not repeat the old SDK-blocker claim below.
  Fresh generation completed: 1,051 files / 20,292 declarations, six generated
  modules / 5,167 assets, zero fallbacks/raw/unresolved syntax, 403.82s wall.
  Native source inventory grew with the pinned GUI dependency ports. Subsequent
  standalone materialization found quoted `_` reference identity in i18n;
  generic converter repair now passes native/JVM tests. Related identifier
  checks: five distinct tests / 21 assertions (overlapping runs 2/11 and 4/14).
  Compact EDN report serialization passes 1/2; a real 16.7 MB catalog roundtrip
  takes 295 ms. Full generation after that change finished in 315.54s versus
  380.30s preceding run, same 1,051 / 20,292 counts and zero fallbacks. This
  saves about 17%; remaining source formatting is still expensive.
  Independent standalone rebuild completed: 6,218 files materialized in 172.86s,
  native build exit 0 in 39.95s, total 212.81s; rebuilt VT dylib is 1.9 MB.
  Five native/JVM tests now pass 29 assertions, including actual terminal/arena
  cleanup and generic queue behavior 4 -> 3 -> 4 with terminal identity retained.
  Queue benchmark matcher now compares forms structurally with the qualified
  operator. The follow-up focus reload now passes after full preparation:
  73 -> 88 -> 73, same terminal, 505.4 ms for the newly compiled change.
  Generic batched registration now republishes refreshed Var metadata; the
  reduced regression and related converter tests pass 3/13. Inspection loads
  converted dependencies before snapshotting (3 assertions). Full runtime
  tests: 50/2,257. Container member functions now shadow namespace aliases;
  this fixes compositor's false `aguafria.zig` import. Emitter: 68/397 passes.
  Logs /tmp/aguafria-ghostty-{rebuilt-jvm-tests,queue-reload-test}.log and
  /tmp/aguafria-ghostty-{focus-plan,dispatch}.edn.
  Explicit :precompile alias analyzes live, bridge, queue-bridge. Final AOT:
  3/3 functions, 14/14 operations, zero baseline failures/gaps, one 42-handler
  bundle, 304,288 code bytes + 608,587 debug bytes. Incremental retry 22.70s,
  35 handlers reused; first pass was 140.76s. Do not rerun source generation.
  Fresh Ghostty49686 returns [1 73 79 4] with zero native compilations:
  four bundle hits, three disk hits, two shared bundles loaded. Cold source
  graph initialization remains slow: 145.73s, versus 10.42ms repeated calls
  (12 memory hits). New focus reload test passes 1/4, six distinct project
  JVM tests / 33 assertions total. Owned Ghostty49509/PID48876, fresh49686/
  PID50372 and root49452/PID47922 are stopped after native cleanup. User
  CIDER56037, La Professeure60588/PID19986 and GUI windows untouched.
  Next categories: source-graph startup performance, remaining project AOT
  (TigerBeetle, La Professeure, Racing), then affected Learn verification.
  New logs /tmp/aguafria-ghostty-{precompile-container-fixed,fresh-aot-reuse}.log.

- Historical Ghostty app follow-up (superseded by the checkpoint above): real
  GUI verification requested, not yet achieved. Full macOS Zig library now
  passes 221/221 steps; targeted GUI/config/font/tmux tests pass 219/219 across
  81 build steps, log /tmp/aguafria-ghostty-gui-tests-017.log. The source port
  now includes pinned zig-objc/zigimg source ports, new reflection and sentinel
  APIs, CoreFoundation/CoreGraphics fieldwise conversions and Metal's unchanged
  64-byte projection payload represented as four arrays instead of extern
  vector fields. Current build log /tmp/aguafria-ghostty-app-017.log.
  macos/build.nu is required for Swift builds. Nushell 0.116.0 was installed
  through Homebrew for it (Homebrew also updated itself, OpenSSL and CA certs).
  Swift build log /tmp/aguafria-ghostty-swift-017.log. Installed Xcode 16.1 /
  macOS 15.1; upstream HACKING requires Xcode 26 + macOS 26 SDK. Do not weaken
  upstream UI features or claim visible success using the user's installed app.
  Direct Swift typecheck of Color.clear.glassEffect() fails with missing-member
  error despite its macOS26 availability annotation. Two SwiftPM-stalled app
  build attempts were terminated; no native UI observed. Ask for Xcode26/SDK
  setup before claiming that check can finish; do not silently port away GUI
  features. Owned Ghostty nREPL64261/PID24469's vendor 0.17 VT terminal was
  closed, and the completed server plus root61748/PID51448 were stopped. No
  user Ghostty windows touched. Keep preview63979 and La Professeure60588.
  New discard-assignment expression conversion regression passes 1/4 including
  ordinary generated namespace evaluation/native calls. Stale-file materialize
  regression passes 1/13, build-profile option checks 2/8. Last full generation
  957 files / 18,434 declarations must be refreshed after vendor ports settle.
  Existing standalone VT artifact is stale; use vendor library explicitly until
  rebuilt. Complex reload benchmark remains unverified (previously blocked by
  discarded `_` expression; its native queue behavior assertion needs review).

- Scope update: the user does not care about Windows for these projects. Do not
  spend more migration/test time on Windows. Prioritize macOS/JVM/hot reload.
- Tiger allocator follow-up: fetched origin's default main at 6f8e6b58d, already
  merged. Preserved its all-build-mode safety allocator policy and original
  backings; all 11 DebugAllocator sites now use SafeAllocator. Fixed parent
  alignment recovery in Context. No fast allocator policy was introduced.
  Targeted client unit 13/13, integration 14/14, existing three client script
  scenarios against a real server, ReleaseSafe server version, and fresh-JVM
  main loading/native-host tests 2/8 pass. Scripts are always Debug; passing
  -Drelease does not turn that script into release-client runtime evidence.
  Fuzz/VOPR/scripts/Vortex-driver builds pass. Before the scope change, C client
  static/dynamic builds passed for all seven targets (20/20 steps). Windows
  SEH required retaining target-default unwind tables; fixed, no follow-up.
  Regeneration: 245 files / 4,443 structural declarations, no fallback forms,
  112.42s including setup. Main's removed allocator alias explains the -1.
  Final generated build.clj refresh also completed: 15.41s wall / 12.35s
  conversion, same 245 / 4,443 counts. No unchanged native tests repeated.
  Current primary work is Ghostty.

- Ghostty macOS follow-up: build configuration and Unicode generation now
  succeed (27/41 VT steps before source compilation). libxev, vaxis, z2d and zf
  have reviewable local source ports at Ghostty's pinned revisions; provenance
  is in vendor/ghostty/pkg/ZIG_0_17_PORTS.md. No edits in zig-pkg. uucode uses
  its upstream 0.17 commit 1fb73433, with unchanged Unicode data. A malformed
  new global package archive was moved to /tmp/aguafria-uucode-cache.rQoDn6;
  ordinary refetch works. 99 removed array-repetition expressions were ported
  using old-Zig AST spans (42 mechanical files plus five manual cases).
  New targeted native checks: struct 3, union 4, packed metadata 1 and integer
  parsing 1 all pass. Current compile migration covers reflection, bit sets,
  BufferFirstAllocator, unmanaged pin pools and TinyIo. Latest diagnostics:
  /tmp/aguafria-ghostty-vt-017.log. VT library now builds 41/41 steps. The
  filtered TinyIo profile passes 109/109 tests across Zig and C ABI roots
  (23/23 steps), log /tmp/aguafria-ghostty-tinyio-017.log. New relative output
  paths required fixing libsystem_override.sh before it changes directory.
  Broader changed VT test bodies, full macOS library, generation and ordinary
  JVM/hotreload remain to be verified.

- Current checkpoint: the previous shader turn made verified progress; actual
  game, Studio and Racing GPU replacements passed, with compiler/GPU failure
  retention and restoration. See root SHADER_MIGRATION_REPORT_2026-10-03.md.
  Three finished test JVMs and Racing desktop stopped; La Professeure remains
  live on 60588. The old ports/disk blockers below are historical, superseded:
  user-approved cleanup freed 169.05 GiB, preserving the shared Aguafria cache.
- [x] Post-core/shader Learn regeneration and live-page verification complete.
  Completed owned nREPLs 61429 and 61249 are stopped. Current report:
  `build/refresh-zig-017-corrected.edn`: 291/291 outcomes, 288 real REPL
  transcripts, zero missing outputs, 14 translated blocks, 7 Zig-only blocks,
  1,340 published inline matches, HTML acceptance passed. All 11 browser tests
  passed; inspected the rebuilt custom-panic REPL panel in the actual browser.
  Preview runs independently on port 63979, Python PID 48021 (exec 42931).
  The five Learn suites checked 99 tests / 12,900 assertions and found only
  two comment-fidelity failures. Restored those upstream comments; the complete
  fidelity suite then passed 2 tests / 1,158 assertions. Unaffected passing
  suites were not repeated. Incremental refresh reran only the two changed
  lessons and the affected block, retaining valid outcome records.
  The earlier `build/refresh-zig-017-final.edn` records the original failures;
  `build/source-fidelity-017-final.edn` records the successful repair check.
  Custom panic calls process.exit(1): the disposable worker now checkpoints
  its active evaluation and recovers actual native output from its own temp
  directory. HTML labels the isolated exit; no invented Clojure exception,
  copied Zig output, or runtime change. Focused recorder checks: 4 / 39 pass.
  Three state-mutating lessons required one fresh JVM after a repeated full
  pass; no hidden resets were added. Remaining migration work is below.

- [ ] Current TigerBeetle follow-up (supersedes old diagnostics below): full
  native unit suite 344 passed / 1 skipped; integration 266 passed / 3 skipped.
  VOPR, fuzz, scripts and all seven Node targets now build; Node 26/26 steps.
  Logs: `/tmp/aguafria-tiger-017.7IRt8K/{unit,integration,node-final3}.log`.
  Converted containers may reference their actual parsed members, including
  mutually recursive types; unknown names and authored ordering remain strict.
  Fixed inferred `.init()` calls, tuple-valued switch branches, sequential
  while-option scopes and nested local/namespace-alias collisions.
  Latest emitter: 67 tests / 392 assertions. Runtime: 49 / 2,254. Non-Tiger
  converter checks: 44 / 280 across the full run and new focused deltas.
  API, clj-kondo and SPIR-V suites: 26 / 203. All pass. These include null
  sentinel pointers, callback parameter names as lexical data, reserved import
  aliases and native math instruction disassembly.
  Latest completed conversion: 245 files / 4,444 structural declarations,
  no raw/unresolved/fallback forms; 117.37 seconds including setup.
  Ordinary require and generated native `version!` now succeed (exit 0).
  Callee reload now passes two assertions: result changes true -> false while
  the caller generation stays unchanged. Three generic linkage corrections:
  discarded module imports do not seed all public hooks; ordinary libraries
  do not seed test-body references; computed const initializers remain lazy
  until consumed, just like simple re-exports. The last case was stdx's private
  `snap = Snap.snap_fn(...)`. No builtin.is_test assertion was weakened.
  New converted native fixture passes three assertions: library load, ordinary
  answer call, and explicit Zig test execution. Full runtime 49 / 2,253 passed;
  the subsequent consumed-computed-constant assertion also passes (2,254 total).
  All six fresh Tiger loading/host/type-reload checks now pass 16 assertions.
  The five-test group found one stale expected declaration count (42): current
  conversion report and source have 41. Corrected that fixture expectation;
  its targeted rerun passes all five assertions. Native host exits 0.
  Owned root nREPL 61748 (PID 51448, exec 50720) and Tiger nREPL 62198
  (PID 58064, exec 14827) was used for diagnosis and has now been stopped.
  Do not repeat the unaffected 610 native tests.
  Post-linkage Learn refresh is complete: 291 passing outcomes, 288 transcripts,
  zero missing outputs, all 11 browser/highlighter checks pass. The real browser
  was reloaded and Hello Again's a/ source plus output inspected.
  nREPL63239 initially picked up system ZLS0.16 and correctly rejected it at the
  HTML stage. Its successful outcome results were retained; only HTML was run
  in nREPL63374 with AGUAFRIA_ZLS pointing at the installed 0.17 binary:
  `/Users/pfeodrippe/Library/Caches/aguafria/zls/eab2be0fd74443a27662da809b771c4ad0d2afcf/bin/zls`.
  Combined evidence remains build/refresh-after-tiger-017.edn. Completed Learn
  nREPL63239 and nREPL63374/PID14160 are stopped. Core61748 remains useful.
- [x] Use `a/` as the maintained API alias: 533 authored/config/test/doc files,
  converter output and formatter configuration. Preserve the user's hello.clj
  and scratch.clj. Existing fixture alias `a` became `dependency-a`; generated
  Zig modules named a/k cannot shadow API aliases. Native regression returns 84.
- [x] Final post-alias Learn HTML refresh completed in fresh owned nREPL 62707
  (PID82840, exec30242), using `/tmp/aguafria-zig-017-investigation.flC57s/learn-after-tiger.clj`.
  Evidence: build/refresh-after-tiger-017.edn. All 291 outcomes pass, including
  286 native comparisons and five reviewed special cases. HTML contains 288
  actual REPL transcripts, zero missing outputs, 304 tabbed figures and 1,340
  matching published inlines. Translation, blocks and inline checks pass.
  Earlier Learn nREPLs 62200 and 62581 were stopped because source changed.
  All 11 browser/highlighter tests pass, including the new `a/` HTML assertion.
  Reloaded the actual app browser and verified hello_again's a/defn plus real
  Hello World REPL output. Protected hello.clj intentionally retains az/.
  Browser tests need NODE_PATH set to the bundled Node modules:
  `/Users/pfeodrippe/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules`.
  Run test/layout_test.cjs and test/highlight_test.cjs; the first bare Node
  invocation lacked Playwright, then the configured run passed all five tests.
  Keep the preview server available independently of the finished build JVM.
  Completed Learn build JVM 62707/PID82840 was stopped after verification;
  preview PID48021 on 63979 remains up. Normal `clojure -X:prepare` completed
  successfully; the ignored std catalog now also uses the new alias and has
  no remaining az/ references. No generated metadata was committed.
- [ ] Ghostty now uses bundled `b.addTranslateC`, replacing the external
  Codeberg translator dependency. Ported Build.root access, optional findProgram,
  feature reflection field_names, sentinel formatting and initial lazy install
  paths. Removed obsolete Apple math.h shim: 0.17's float.h implements the
  __need_infinity_nan protocol; removed file is recoverable from vendor Git.
  Feature parsing native tests: 2/2 pass. All 16 changed vendor files format.
  Full build still fails with nine diagnostics in two current categories:
  seven build-graph API errors (GhosttyResources install_prefix; libxev's
  findProgram; four z2d install_prefix sites; zf's b.args), and two reflection/C
  type errors (vaxis std.meta.fields; Oniguruma optional cTypeBitSize).
  Dependencies under zig-pkg are untouched content-addressed packages; migrate
  them through reproducible source dependencies, not edits to package caches.
  More diagnostics may follow after configuration succeeds. No successful full
  0.17 Ghostty build, conversion or JVM/reload evidence yet. Latest build command:
  `zig build -Demit-lib-vt=true -Demit-xcframework=false -Demit-macos-app=false --summary all`.

- [x] Added project request: migrate authored game/shared shaders to Aguafria
  Zig with SpirvType and embedded-Zig SPIR-V builds, remove replaced GLSL,
  and verify actual render-thread GPU pipeline hot reload with visible output,
  bad edits and recovery. Keep this separate from CPU hot-reload evidence.

- [x] Verify official release, compiler checksums and source tag; create the root
  migration report `ZIG_0_17_0_MIGRATION_2026-10-03.md`.
- [x] Import the official HTML and source snapshot: 291 files, 1,379 snippets;
  23 changed files, 8 additions and 9 removals compared with 0.16.0.
- [x] Regenerate keyword and std catalogs from the 0.17 compiler and compatible
  ZLS: 128 builtins and 33,933 public std declarations.
- [x] Replace reflection transport/probe APIs and removed array repetition.
- [x] Rename five lessons, remove four obsolete lessons and the removed C-import
  fragment, add two new executable lessons, remap authored fragment IDs.
- [x] Use `{:type ...}` for enum/packed-struct backing types and union tag types;
  migrate all eight remaining authored lessons and reject the old option.
  Retain the backing type in ABI schema identity. Final API/emitter checks:
  67 tests / 474 assertions; fresh native field-view checks: 1 test / 4 assertions.
- [x] Full outcome checkpoint: 286 comparisons plus five reviewed special cases,
  zero failing artifacts. Record four tagged-source/published-HTML differences
  without changing either upstream input.
- [ ] Finish core/native loader and affected project compatibility checks.
- [x] Finish full Learn translation and native/JVM outcome comparisons.
  Ordinary JVM body/subform checks: 3 tests / 84 assertions, no failures/errors.
- [x] Run Learn 0.17 compile-only AOT and a fresh-JVM values cache proof:
  2,147/2,670 operations, 2,147/2,523 runtime candidates, 6m35s. One library:
  2,098 handlers, 12.8 MB code / 28.6 MB separate debug data. Fresh ordinary
  values body: 928 ms, 34 bundle hits, zero compilations, seven output checks.
  Report `.aguafria/precompile/learn-zig-017.edn`; exclusive remaining categories
  are in the root migration report.
- [x] Exact AOT reconciliation against the 0.16 checkpoint: 2,293 common
  namespace/operation-ID/form identities, zero prepared-operation regressions.
  Keep 415 old-only / 377 new-only identities separate from exact matches.
- [x] Preserve published compilation snapshots when demand-loading JVM
  adapters, including retained ABI versions. Keep adapter publication separate
  from definition/type/state publication. Independent recovery declarations
  can publish while an unrelated failed state edit remains pending.
- [x] Fresh-JVM hot-reload group: 9 tests / 99 assertions, zero failures/errors.
  Runtime unit suite: 45 tests / 2,234 assertions, zero failures/errors.
  Includes host pinning, cyclic type adoption, retained callers/ABIs, in-flight
  retirement, three state-migration scenarios and atomic component rollback.
- [ ] Finish remaining core/project checks; refresh Learn output/HTML evidence
  after the final runtime fixes. Do not rerun the completed targeted group
  unless a relevant publication/adapter change requires it.
  Current owned core nREPL is 53534 (PID 74952, exec session 52502);
  53017 and 52297 were stopped.
  Full historical failing
  integration evidence is `/tmp/aguafria-zig-017-investigation.flC57s/integration-retry.edn`.
- [x] Replace removed build-runner patching with the 0.17 build-server protocol.
  Capture selected generated-module/path producers without running application
  steps; test empty/unknown profiles, errors and independent build-file caches.
- [x] Fix retained-root generated-module dependencies in JVM adapters, nested
  converter declaration ordering and destructuring validation. Checked set:
  38 converter + 46 runtime + 60 emitter tests / 2,850 distinct assertions.
  One stale ordering assertion from the broad run was corrected and rerun;
  targeted container + emitter proof is 61 tests / 364 assertions, all passing.
  See root migration report and `/tmp/aguafria-zig-017-investigation.flC57s/converter-check*`.
- [x] Correct generation-count expectations for separate native implementation
  and first-call JVM adapters: two tests / 17 assertions. Port the bundle test
  to a named translated-C module; update native-value test assertions and the
  0.17 pointer-alignment reflection fixture.
- [x] Fix quoted container-field emission and shared state lookup. Emitter,
  converter-alignment and container-state checks pass 62 tests / 375 assertions;
  a dedicated quoted/hyphenated shared-state regression passes 1 test / 4 assertions.
- [x] Fix pure-Zig editor reference validation after conversion/re-homing.
  Only parsed converted declarations and already registered same-module names
  are accepted; unknown and unevaluated authored references remain errors.
  The existing 13 editor tests and 60 emitter tests pass. Three added regressions
  pass 10 assertions, including exact AST token spans with Unicode/comment
  decoys and preservation of the actual error message.
- [x] Evaluate preceding declarations before extracted pointer lesson bodies.
  The new 0.17 endian switch revealed missing JVM scoped-switch support. Reuse
  native scoped execution, with lazy branch selection, native result transport,
  shared mutable captures and inline error propagation. Zig @TypeOf determines
  payload types; type-only analysis never runs user branches. Final focused
  pointer/switch/loop/block checks: 6 tests / 42 assertions, no failures/errors.
  Evidence: `/tmp/aguafria-zig-017-investigation.flC57s/scope-final.edn`.
- [ ] Continue remaining native suites and TigerBeetle/project upgrades:
  fresh-child classpath/catalog loading, vendored 0.16 syntax and affected
  project native builds/hot reload/AOT remain pending. Do not repeat completed
  checks without relevant changes. La Professeure ordinary studio require now
  succeeds in owned nREPL 53677 (PID 79651, exec session 33388); reuse it for
  upcoming native checks. Core/dialogue: 11 tests / 162 assertions passed.
  Disk is ~1.3 GiB free. An unanswered cleanup question concerns only the
  regenerable `/Users/pfeodrippe/.cache/zig` compiler cache. Nothing was deleted;
  preserve the prepared Aguafria cache. Defer large builds until space is available.
- [x] Fix six TigerBeetle fresh-child classpaths to include the prepared std
  catalog. The next failure is stale generated code (`counting_allocator/alloc`),
  not missing std namespaces. Do not rerun that load test until regeneration.
- [x] Port TigerBeetle's build configuration without reverting its current
  source revision. Three configurations plus generated-source helper behavior:
  2 tests / 27 assertions, zero failures/errors. Fetch helper compiles.
  Evidence: `tiger-build-helpers-017.edn` under the investigation directory.
- [ ] Finish TigerBeetle application-source migration, then regenerate and run
  its fresh/native/hot-reload tests. Removed array repetition and errdefer
  captures were ported. Latest compile-only first diagnostics were legacy
  reflection in config/tabular; both patched, tabular native test passes.
  Regeneration currently encounters removed `@cImport` in the C header test;
  migrate all three import sites to translated-C build modules. Do not rerun
  stale generated-code loading before regeneration succeeds.
- [x] Merge current origin/default branches normally, without rebase/replay:
  TigerBeetle merge `ab9e51a29` includes origin/main `6f8e6b58d`; Ghostty merge
  `e1e7db31e` includes origin/main `befcdfd2c`. Both ancestry checks pass.
  Tiger build/helpers rechecked after merge: 2 tests / 27 assertions pass.
  Four conflicted Ghostty Swift files pass installed Swift 6.0 parser checks;
  swiftlint is unavailable. No full application build or push is claimed.
- [x] Remove Lightpanda, tigerbeetle-zig, ghostty-zig and Field Lab as requested.
  Exact directories are recoverable under ~/.Trash/aguafria-<name>-2026-10-03.
  Remove obsolete active links/submodule registration, preserve recovery data.
- [ ] Verify each remaining runnable project with real owned nREPL and live
  native hot reload; see PROJECT_NREPL_VERIFICATION_2026-10-03.md. Current HTTP
  HTTP check completed in fresh nREPL 54946: 1 test / 19 assertions, all pass.
  Both owned HTTP REPLs were stopped, listener closed. Package fetch fixes use
  ZIG_GLOBAL_CACHE_DIR and separate stderr/hash output: 2 tests / 9 assertions.
  Racing nREPL 54953 (exec 32533) is loading racing-game.core normally.
- [x] Finish named translated-C module wiring and retained-root type closure.
  Latest runtime suite: 47 tests / 2,239 assertions; C suite: 4 / 29; added
  native retained-root/layout-change regression: 1 / 3. Game safe checks:
  3 / 51; miniaudio generator check: 1 / 62. All pass. Earlier 31 game errors
  reduced to four, then zero; final log is `game-safe-retained-017.log`.
- [x] Persist matching ZLS from verified upstream commit
  `eab2be0fd74443a27662da809b771c4ad0d2afcf`; it reports 0.17.0-dev (no tagged
  0.17 ZLS release yet). Stable executable:
  `/Users/pfeodrippe/Library/Caches/aguafria/zls/eab2be0fd74443a27662da809b771c4ad0d2afcf/bin/zls`.
  Three real hover checks pass. README/report document the explicit
  AGUAFRIA_ZLS setting and reproducible build; no editor config changed.
- [ ] Refresh Learn outputs/HTML after the latest scoped-JVM, reference and
  retained-root fixes; verify the live page. Use the stable AGUAFRIA_ZLS path
  above for newly started processes. Current core REPL still inherits the
  equivalent temporary executable. Disk is ~1.5 GiB free; cleanup question is
  unanswered, no cache deleted. Do not start the large full rebuild yet.
- [x] Update stale integration printing/function-doc expectations: 7 tests /
  47 assertions passed. Compiler-diagnostic fixture: 1 test / 11 assertions passed.
- [x] Final HTML: 291 artifact outcomes, 288 file transcripts, 1,340 exact
  published inline matches and no missing REPL outputs. 77 reference tests /
  11,600 assertions and 11 browser/highlighting tests passed after the final
  shared field-view adapter correction and container option cleanup.
- [x] Bind inline declaration source identity to mapping resources so CLI/REPL
  callers produce identical generated source. Verify the live page and keep
  the preview server independent of completed build JVMs.

The October 1 results below describe 0.16.0, not this migration.

## Latest verified checkpoint — October 1

- [x] Final private-scope/cleanup revision: 292/292 outcomes, 99 tests / 12,983
  assertions, 11 browser/highlighting checks, 290 HTML transcripts and no missing
  outputs. AOT 2,172/2,708 in 4m43s, one additional private callback operation,
  no lost IDs or prepared regressions. Four generated destructuring temporary
  renamings explain 14 raw form-string differences; checked AST renaming proves
  no semantic changes. Fresh ordinary values body 671 ms, 34 bundle hits, zero
  compilations; normal require also zero. Current candidate gaps: 389, plus
  34 deferred calls and 113 declarations/directives. Full categories, evidence
  and separate fresh-proof limits are in `CORE_REGRESSION_CHECK_2026-09-30.md`.
  Report: `.aguafria/precompile/learn-private-scope.edn`.
  Both owned Learn JVMs are stopped; the preview on 63979 remains available.

- [x] Checked member-argument adapters and compile-only refresh reuse:
  292/292 outcomes; 99 tests / 12,983 assertions; 11/11 browser checks; all
  290 HTML transcripts present. AOT 2,171/2,708 in 5m23s, zero gained/lost
  operations or changed forms. Fresh owned nREPL values body 909 ms, 34 bundle
  hits, zero compilations; normal require also zero compilations. Live page
  reloaded and output confirmed. Completed Learn workers and REPL stopped.
  Existing 390 native-candidate gaps remain, with full categories/examples in
  `CORE_REGRESSION_CHECK_2026-09-30.md`.
  Report: `.aguafria/precompile/learn-member-current.edn`.

- [x] Callee-reflected JVM wrappers and preparation performance revision:
  292/292 outcomes; 99 tests / 12,983 assertions; 11/11 browser checks; all
  290 transcripts, zero missing outputs. AOT unchanged at 2,171/2,708 in
  10m11s (concurrent game work and changed wrappers), zero lost/changed forms.
  Fresh nREPL values body 971 ms, 34 bundle hits, zero compilations; normal
  require also zero compilations. Existing 390 native-candidate gaps remain.
  Evidence and limitations: newest `CORE_REGRESSION_CHECK_2026-09-30.md` section.
  Report: `.aguafria/precompile/learn-reflected-current.edn`.

- [x] Typed-call/deferred-assignment revision: 292/292 outcomes; 99 tests /
  12,983 assertions; 11/11 browser checks; all 290 HTML transcripts present.
  AOT 2,171/2,708 in 5m30s, zero lost/changed operations. Fresh owned nREPL:
  values body 745 ms, 34 bundle hits, correct output, zero compilations;
  ordinary require also zero compilations. Current candidate gaps: 390;
  separately 34 deferred calls and 113 declarations/directives. Exact counts,
  two examples per category, evidence and limitations are in the newest section
  of `CORE_REGRESSION_CHECK_2026-09-30.md`.
  Report: `.aguafria/precompile/learn-typed-current.edn`. Served/file SHA-256:
  `31d09de1e15d23cb48b7b996601a6b65e11e7fe091929c8812f79c0adc29ec37`.

- [x] Batch-validation revision: 292/292 outcomes; 99 tests / 12,983 assertions;
  11/11 browser checks; rebuilt HTML has all 290 transcripts and no missing outputs. AOT remains
  2,171/2,708, zero gained/lost/changed operations, in 5m27s. Fresh owned nREPL
  values body 979 ms, zero compilations; normal require also zero. Details and
  unchanged gap categories: `CORE_REGRESSION_CHECK_2026-09-30.md`.
  Report: `.aguafria/precompile/learn-batch-current.edn`. Served/file SHA-256:
  `26457ef279645fdba6e6419eca619fe28d8384fc2f5f1c76129ae308a844383d`.

- [x] Argument-alias revision AOT: 2,171/2,708, zero gained/lost/changed forms,
  5m48s. Fresh JVM normal require 2,760 ms and values body 986 ms, both with
  zero compilations. Report: `.aguafria/precompile/learn-argument-alias.edn`.
- [x] Final transcript/HTML refresh after the wrapper-cache key correction:
  292/292 outcomes; 99 tests / 12,983 assertions; 11/11 browser checks; all 290
  transcripts present. Served/file SHA-256:
  `9572b02707bc321a81c96320d20c3c465bb90c56eba848f437f0feb48a2be812`.
  The first HTML check rejected 251 stale outputs rather than publishing
  missing panels; the final compiler-fingerprint refresh passes.

- [x] Index/registry revision: 292/292 outcomes; 99 tests / 12,983 assertions;
  11/11 browser checks; rebuilt HTML has 290 transcripts, zero missing outputs.
  AOT 2,171/2,708 in 5m59s, zero lost operations or changed forms. Fresh JVM
  values body 969 ms, zero compilations (normal require also zero). Report:
  `.aguafria/precompile/learn-indexed-registry.edn`. Served/file SHA-256:
  `337c101116fdb7449b55edc61721a2f145ee7a034d0d8ab3e0448a3d69e0b67d`.
- [x] Shared-core index/registry checks: 39 tests / 2,257 assertions, zero
  failures/errors, including native state preservation and hot reload.

- [x] Composite-input revision: 292/292 outcomes; 99 tests / 12,983 assertions;
  11/11 browser checks; rebuilt HTML has all 290 transcripts, none missing.
  Served/file SHA-256:
  `9a9b2e333dd1c963494f372f4ee30beacf8715939cbdfcfa5ed791d1e1cb114a`.
- [x] `learn-composite-final` AOT: 2,171/2,708, 11 gained, zero lost or changed
  forms, 6m52s. Fresh JVM values body 747 ms, zero compilations; require also
  zero. Fix the broader suite's assignment-context regression; the extended
  test plus two adjacent tests pass 36 assertions. Owned REPL stopped.
- [ ] 537 entries remain (113 non-calls, 34 deferred calls, 390 runtime candidates); categories
  and two examples each are recorded in `CORE_REGRESSION_CHECK_2026-09-30.md`.

### Earlier function-alias checkpoint

- [x] Function-alias revision: 292/292 outcomes; 99 tests / 12,983 assertions;
  11/11 browser checks; HTML rebuilt with all 290 transcripts, none missing.
  Served/file SHA-256:
  `dc0dac2f033c8b98b380b1d0e4f962668928d4feb83caa288deccbd05c20e04d`.
- [x] `learn-aliases` AOT: 2,160/2,708, zero lost operations or changed forms,
  6m27s. Fresh JVM values body 864 ms, zero compilations; require also zero.
  Broader core suite: 63 tests / 490 assertions, zero failures/errors. Owned
  verification REPL stopped; live browser reloaded and checked.

### Earlier integer-reuse checkpoint

- [x] Latest integer-reuse/conditional-declaration revision: 292/292 outcomes,
  99 tests / 12,983 assertions, 11/11 browser checks; rebuilt HTML has 290
  transcripts, zero missing outputs. Served/file SHA-256:
  `642b8460129fbc7e1754dcd93eb0610cdc8f7399ef65ae05d23684d2c42bbee8`.
- [x] Latest AOT: 2,160/2,708, zero losses or changed forms, 4m58s. Fresh JVM
  values body 1,248 ms, zero compilations (initial require also zero).
  Report: `.aguafria/precompile/learn-integer-branches.edn`.
- [x] Broader core regressions: 62 tests / 475 assertions, zero failures/errors.
  Owned verification REPL stopped; live preview reloaded and checked.

### Earlier transport checkpoint

- [x] Final C-constant/error-set transport regression: 292/292 outcomes,
  99 tests / 12,983 assertions, 11/11 browser checks; rebuilt and served HTML
  has 290 transcripts and zero missing outputs. SHA-256:
  `250b61619cb6f07ffe95f08f7c00cb4d6e78a2cba0f969f305602261bd6c6cae`.
- [x] Final AOT: 2,160/2,708 prepared, no regressions, 7m24s. Fresh JVM:
  require 2,732 ms (zero compilations), complete values body 728 ms (zero
  compilations). Report: `.aguafria/precompile/learn-c-constants-final.edn`.
- [x] Fix the isolated cold-cache changed-integer coercion miss: 2 tests /
  23 assertions pass in a fresh JVM and empty cache, including range checks.
  Conditional C-declaration/error-set tests pass 19 assertions. The broader
  suite and full Learn verification are complete in the latest checkpoint above.

- [x] Recheck local-type identity, constant/NaN transport and explicit private
  definition validation changes:
  292/292 outcomes; 99 tests / 12,983 assertions; 11/11 browser checks pass.
- [x] Rebuild HTML: 290 actual REPL transcripts, zero missing outputs. Live page
  reloaded; served/file hash is
  `ad6a575b9ad8d6a038659c244ae35f0b18c7a7bb2ac6e2080b299cb26eb61f2a`.
- [x] Full AOT: 2,160/2,708 operations prepared in 7m58s. Zero regressions across
  the complete inventory. One 1,884-handler bundle. Fresh-JVM `values` body:
  987 ms, zero compilations; initial require compiled one namespace module.
- [x] Focused runtime regressions: two groups of 8 tests / 52 assertions each.
  Discovery/identity suite: 58 tests / 441 assertions, including restart checks.
- [ ] Native-library stress ceiling: macOS exhausted TLS pthread keys during
  the single-JVM 75-test suite. Isolated tests pass, but the ceiling remains open.
- [ ] The remaining AOT gaps are categorized with examples in
  `CORE_REGRESSION_CHECK_2026-09-30.md`; this is not complete JVM-subform coverage.

## Resume checkpoint — September 30

### Cache-key and computed-constructor follow-up

- [x] Fix both cold-preparation/restart misses: nested tuple repetition and
  container-variable accessor identity. Focused checks: 89 + 3 assertions pass;
  preparation loads no native bodies and the fresh JVM compiles zero adapters.
- [x] Preserve numeric precision: five tests / 28 assertions pass. Recognize
  compiler primitive `undefined` for computed constructors: five assertions pass,
  with no undefined-storage read and no call-time compilation.
- [x] Refresh full Learn AOT, outcomes, HTML and browser checks after these fixes.
  292/292 outcomes, 99 tests / 12,983 assertions, 11/11 browser checks pass.
  AOT: 2,158/2,708 operations prepared in 4m32s, no coverage regressions.
  Broader discovery/identity/pointer suite: 58 tests / 436 assertions pass.
  Fresh-JVM `values` body: 659 ms, zero compilations. HTML has 290 transcripts,
  zero missing outputs; served/file SHA-256:
  `91f9c0763beaf90163fb872a911083ce8bd60f6a56a6c6b18ea4121042750c25`.
  Logs: `.tmp/learn-cache-*.log` at the repository root. The 550 AOT gaps remain
  categorized in `CORE_REGRESSION_CHECK_2026-09-30.md`.

### Latest literal/type-equivalence regression check

- [x] Current shared-code Learn recheck: 292/292 outcome comparisons; 99 tests /
  12,983 assertions; 11/11 browser checks. HTML rebuilt with 290 REPL transcripts
  and zero missing outputs. Served SHA-256:
  `43c3081cfb6edfe2a06ec97e0ad0fcacd166dca4cd0ec2ed5167a0ccbd9d7638`.
- [x] Full Learn AOT finished in 4m41s: 2,158/2,708 operations prepared, 550 gaps
  (previously 2,157 prepared / 551 gaps). One 1,871-handler bundle produced.
  No previously prepared operations regressed at this checkpoint.
  Fresh-JVM `values` body: 688 ms, 34 bundle hits, zero compilations.
- [ ] Two previously prepared `test_functions` observations are now missing
  while its naked-function baseline fails; keep this coverage regression visible.
- [x] Restore concrete body validation in unlinked extern modules. Expanded
  regression passes 11 assertions, including scalar/aggregate invalid bodies
  and generic declarations. Validation does not link unresolved externs.
- [x] Preserve comptime literals and parameters in contextual AOT plans.
  `fieldParentPtr` and literal `ptrFromInt` pass focused ordinary JVM checks
  with zero compilation after preparation. Full Learn recount/HTML refresh
  completed for these newer shared-core changes.
- [x] Contextual integer/float/nested-pointer casts reuse persistent adapters
  after restart: 478 ms, three disk hits, four bundle hits, zero compilations.
- Detailed categories, examples, counts and logs:
  `CORE_REGRESSION_CHECK_2026-09-30.md`. Verification JVMs stopped; preview stays up.

### Current game-core regression recheck (completed; AOT gaps remain)

- Initial complete Learn suite: 96 tests / 12,961 assertions, one failure and
  two errors. The failure is the missing upstream `native_arch` binding in
  `test_noreturn_from_exit.clj` (restored). Both errors are the HTML guard
  rejecting 251 missing/stale current-compiler REPL transcripts.
- Whole-Learn compile-only audit completed: 289 selected namespaces plus one
  explicit quota-example exclusion; 2,117 / 2,708 operations prepared, 591 gaps.
  The previous report had 2,115 / 2,706 and the same 591 gaps. Handler failures
  (97) and declared-function failures (18) match that baseline; these are not
  all silently considered expected. Individual prepared-operation comparison
  differs only for the intentionally invalid constant-string-to-mutable-slice
  call, now unobserved. Report: `.aguafria/precompile/learn-game-regression-fixed.edn`.
  Duration: 229,684 ms. Current native outcome refresh: 292 / 292 comparisons
  pass (288 upstream outcomes and four reviewed special cases).
- The audit exposed inferred-error result readers emitting illegal `*const
  !void` storage. Preparation now uses the same explicit bridge storage type
  as invocation. Preparation/order/result-reader regressions: 4 tests / 38
  assertions pass, including `!u32` and `!void` with no call-time compilation.
- A separate cold-preparation/fresh-JVM check returned correct results but
  compiled two missing artifacts (nested tuple repetition and container state).
  This is not a zero-build pass. Fix/recheck before claiming preparation complete.
- Actual `values.clj` in a fresh JVM: normal require 2,704 ms with one disk hit;
  whole main body evaluated as ordinary Clojure in 863 ms, 34 bundle hits and
  zero compilations. No hidden warmup. `.tmp/learn-aot-values-restart.log`.
- Current fragment checks: 15 translated blocks, 9 Zig-only; 351 authored /
  251 syntax-checked / 77 matching inline outputs.
- [ ] Complete the current inventory and classify real vs expected failures.
- [x] Refresh outcomes/fragments after the final shared-code fix, rebuild HTML,
      rerun the Learn suite/browser checks and verify the served file.
  Current Learn suite: 96 tests / 12,973 assertions, zero failures/errors.
  Browser/highlighting: 11 / 11 pass, including output parity and widths from
  640 to 2,600 px. Rebuilt HTML: 290 REPL transcripts, zero missing outputs.
  A separate served-page hover check displays ZLS's `fn main() void`, without
  a native title tooltip; Escape dismisses it. Both Hello World outputs are
  visible in the inspected screenshot. Served port 63979 matches the file:
  SHA-256 `005025a5dbdd98359696c3f68ea9d790268ff268d332d76b9f5998d44a7c90ed`.
  Logs: `.tmp/learn-game-regression-outcomes-fixed.log`,
  `.tmp/learn-game-regression-html.log`, `.tmp/learn-game-regression-tests-final.log`,
  `.tmp/learn-game-regression-browser.log`. Screenshot:
  `.tmp/learn-served-tooltip.png`. Verification JVMs stopped; preview remains up.

- [x] Use `:align` consistently for native storage, fields and top-level
  declarations. No `:zig/align` uses remain in source, tests or examples.
  Computed native alignments work for `k/var`; invalid alignments fail before
  allocation. Focused alignment/packed-field/destructuring/runtime regressions:
  **53 tests / 275 assertions**, plus **10 live nREPL assertions** on owned port
  60876. Updated an obsolete destructuring rejection test: nested, rest and
  `:as` bindings have been supported already; malformed field keys still fail.
  Logs: `.tmp/align-option-regressions-final.log`, `.tmp/align-option-nrepl.log`.
- [x] Capture actual JVM evaluations for intentionally failing native lessons
  in isolated workers, including panic messages and original Clojure locations.
  Declaration-only lessons capture their actual declarations. Error-union
  return values count as native failures, not successful nil evaluations.
  Cross-target examples record the actual host-JVM compiler diagnostic; this
  does not claim execution on the target architecture. Fixed a diagnostic
  formatter bug where the pseudo-file `test` was read as a directory, masking
  the real Zig error. All seven capture failures exposed by the initial full
  run pass on focused recheck (`.tmp/output-seven-recheck.log`).
- [x] The HTML build rejects translated Shell panels without nonempty REPL
  evaluations; a browser regression checks the rendered counterparts too.
  Capture/parity regression tests: **6 tests / 39 assertions** passed
  (`.tmp/output-parity-tests-final.log`).
- [x] Full regeneration completed: **292/292 comparisons** (288 upstream +
  4 reviewed), **290 nonempty REPL transcripts**, **zero missing REPL outputs**.
  Transcript scopes: 178 in-process recipes, 29 declaration-only, 44 expected
  load diagnostics, 32 isolated native failures, 7 isolated cross-target host
  captures. Fragments: 15 translated blocks; 351 authored / 251 syntax checks /
  77 matching inline outputs. Original upstream HTML preservation passes.
  Served port 63979 matches `build/site/index.html`, SHA-256
  `622b9bd6b413daf222a32f223977780e6d4eaf5767502a8692427fc5879df0fc`.
  Logs: `.tmp/align-output-final-outcomes.log`,
  `.tmp/align-output-final-fragments.log`, `.tmp/align-output-final-html.log`.
  **11/11 browser/highlighting checks pass**, including generic rendered output
  parity and the incorrect-alignment diagnostic. The browser parity check
  excludes panels with no Clojure translation, matching the build guard.
  Log: `.tmp/align-output-final-browser.log`.

- [x] Structured top-level alignment: `{:align 4}` and
  `{:align (k/* (k/sizeOf :usize) 2)}` work on constants, variables, functions
  and extern prototypes, including nested declarations. Qualified alignment
  expressions participate in dependency tracking, cache identity and reload
  compatibility. Zig conversion retains alignment as a parsed expression and
  preserves other qualifiers such as calling conventions. Migrated all four
  raw alignment qualifiers in Learn's variable/function alignment lesson.
  Focused suite: **66 tests / 398 assertions pass**, including converter round
  trips and the real lesson bodies on the JVM plus native tests.
  `.tmp/structured-align-regressions.log`. An initial nREPL test-suite invocation
  hit two fixture-path errors because the old server ran from Learn, not the
  repo root. A fresh owned root REPL passed **5 tests / 41 assertions**
  (`.tmp/structured-align-fresh-nrepl.log`); final regeneration is recorded above.

- [x] Remove redundant `az/type` from known type-argument positions: **31
  wrappers across 15 Learn files**, including `k/as`, builtin reflection/size
  queries, imported functions and user-defined `List`/`LinkedList` calls.
  Native emission now reads builtin/imported parameter types through the same
  Zig signature parser as the JVM bridge; user functions use their declared
  argument types. Composite schemas are emitted as types, not tuple values.
  No Clojure type inference is involved. Keep `az/type` when a type itself is
  the value (for example, the expected value in a type equality assertion).
  Regression suite: **70 tests / 427 assertions pass**, including **3 new tests /
  14 assertions** for direct schemas in both JVM and compiled calls.
  Logs: `.tmp/type-wrapper-regressions.log`, `.tmp/type-wrapper-tests.log`.
  Owned nREPL verification also passed **3 tests / 14 assertions**
  (`.tmp/type-wrapper-nrepl.log`). Output/HTML refresh COMPLETE: **292/292**
  comparisons (288 upstream + 4 reviewed), **202 REPL transcripts**, **307
  tabbed figures**, zero acceptance problems. Fragment refresh: 15 translated
  blocks, 9 Zig-only; 351 authored / 251 syntax checks / 77 matching inline
  outputs. Served port 63979 exactly matches the rebuilt HTML:
  SHA-256 `85b74ced9c10c985f7ddbf42dd27648ece6dad56f2a23e53c992102d40a9e4d1`.
  Logs: `.tmp/type-wrapper-outcomes-final.log`, `.tmp/type-wrapper-fragments.log`,
  `.tmp/type-wrapper-html.log`, `.tmp/type-wrapper-browser.log`.
  Browser/highlighting checks: **10/10 passed**. The source-text walker now
  visits regular files only, avoiding Emacs lock symlinks without deleting them.

- [x] Pointer-schema/alignment follow-up: `:*` accepts options and defaults to
  a single-item pointer. Removed `:pointer` schema support and redundant
  `:size :one` from code/examples; converter and compiler probes emit the same
  canonical schema. Zig reflection's `pointer` variant is unchanged. Computed
  dimensions/options retain Zig expressions or native scalar results. Global
  address-taking preserves explicit alignment from Zig's pointer type; function
  addresses use Zig rather than the mutable-data path. All **4 native tests**
  and their JVM bodies pass in the volatile/variable/function alignment lessons.
  Focused checks: **86 tests / 547 assertions pass**, including pointer discovery,
  converter round trips, packed fields, emitter, preparation, package aliases,
  precision, cache reuse and prior pointer lessons. The discovery test was rerun
  with `test` on the classpath after an initial runner setup error; logs:
  `.tmp/pointer-final-regressions.log`, `.tmp/pointer-discovery-regression.log`.
  Also fixed the missing `rewrite-clj` preparation dependency; Learn `:prepare`
  now succeeds. `k/alignOf` already accepts primitive/composite type schemas
  directly through builtin signatures: verified in the REPL, removed redundant
  `az/type` wrappers, and added three direct-schema assertions. The final two
  lesson edits pass **6 tests / 50 assertions**; see
  `.tmp/direct-alignment-regression.log` (included in the total above).
  HTML refresh COMPLETE: **292/292** comparisons, **202 REPL transcripts**,
  **307 tabbed figures**, zero acceptance problems, **10/10 browser tests**.
  Last incremental refresh reused **290** examples and executed only the two
  modified lessons. Served port 63979 exactly matches the rebuilt HTML:
  SHA-256 `f27cf84993e5424d0eb568ce5605073dab68425e313f290b788a7166d7c3e390`.
  Logs: `.tmp/current-pointer-html-outcomes-final.log`,
  `.tmp/current-html-build-final.log`, `.tmp/current-html-browser-tests.log`.

- [ ] Broader converter suite is not clean: **10 failures / 4 errors** from the
  additional full run (`.tmp/pointer-schema-regressions.log`). Categories:
  missing generated catalog in subprocess classpaths (6 failures; examples:
  fresh namespace loading, callee hot swapping); obsolete generated syntax
  (2 errors; checked TigerBeetle corpus, complete TigerBeetle conversion);
  binding validation (2 errors; destructuring assignments, nested containers);
  raw-JVM-value expectations (4 failures; generated-answer, data-path-length).
  These are outside the focused passing suite; do not report all tests green.

- [x] `k/comptime` is identity in ordinary JVM evaluation: its argument runs
  once and its result is preserved, including nil/false. Inside Aguafria forms
  it still emits Zig `comptime`. Tested the complete pointer-conversion wrapper.
- [x] Reuse imported-call adapters when changing integer/float literals is safe.
  Read the original Zig AST to check that arguments first become a common type
  and are passed to a normal typed helper; no function-name special cases or
  Clojure type inference. Zig `@TypeOf` confirms the type without loading or
  executing native code. Float decimal rounding and integer range checks remain.
  Runtime and preparation share the planner. Package loading provides resolved
  source locations in Var metadata without committing generated source paths.
  Final focused suite: **6 tests / 60 assertions**, zero failures, including
  full JVM comptime body, native pointer tests, safe/unsafe wrapper patterns,
  mismatch/range errors, float midpoint, booleans, aliased package functions,
  resolved package source metadata, and compile-only preparation. Changed
  integer and float values produced **0 compilations / 6 memory-cache hits**
  in live REPL checks. The full JVM `k/comptime` example returned `{:ok nil}`.
  No HTML,
  full Learn audit, cache deletion, or whole AOT rerun in this scoped fix.
- [x] Fixed package catalog visibility: normalize converted `k/pub` attributes
  before collecting public declarations. Alias namespaces (`Bytes`, `BytesAlias`,
  `RowAlias`) and qualified `Buffer/Slice` documentation are restored. Updated
  old raw-JVM assertions to inspect the deliberately preserved native values.
- [x] Fixed standalone comptime-literal precision: keep original Zig expressions
  through coercion and arithmetic instead of round-tripping through JVM doubles.
  `(k/as (az/number-literal "1.0000000596046448") :f32)` now rounds up correctly.
  New regressions compare f16/f32/f64 values inside Zig, including computed and
  negative expressions, exact u64 literals, and overflow rejection. Combined
  package, precision, imported-call reuse, and pointer suite: **12 tests / 104
  assertions, zero failures or errors** (`.tmp/current-regressions.log`). Live
  nREPL checks confirm direct and computed f32 coercions. Included in the HTML
  refresh recorded above.

- [x] Unify compile-time expressions and statements under `k/comptime`; removed
  `az/comptime-stmt` without an alias, migrated converter and nine Learn files.
  Focused checks: **58 tests / 343 assertions**, including a native round trip.
  HTML rebuilt with **202 REPL transcripts**, live served hash matches the file.
  Coarse compiler fingerprint forced a broader **292/292** outcome refresh;
  avoid repeating whole-suite checks for subsequent scoped fixes.
- [x] Reproduced and fixed JVM integer-to-pointer conversion and the actual
  `pointer-casting` body. Result-context builtins retain their operands until
  `k/as` supplies the destination type; no pointee type is guessed. Array
  `{:align ...}` options replace inert local metadata and preserve the original
  Zig binding alignment, plus owned JVM storage alignment. Fixed a nil child
  schema check exposed by native argument transport.
  `jvm_pointer_context_test.clj` reads and evaluates the actual three lesson
  lets (not calls to their compiled tests), then separately runs the native
  tests: **3/3 JVM bodies / 3/3 native tests**, combined focused emitter/native
  suite **60 tests / 363 assertions**, zero failures. Log:
  `.tmp/pointer-context-tests.log` at repository root. No whole-Learn rerun.
  HTML above includes the comptime rename but predates the subsequent pointer
  alignment edit; do not report all JVM subforms as verified from native
  outcome-comparison counts.

- HTML regeneration after the single-library AOT change COMPLETED: refreshed
  **292/292 outcomes (288 upstream / 4 reviewed special cases)**, blocks and
  inline checks, then rebuilt the page with **202 REPL transcripts / 307 tabbed
  figures / 356 sections**. Fresh acceptance check: **zero problems**. Browser
  layout **4/4**, highlighting **6/6** pass. Live port 63979 serves exactly the
  rebuilt file (SHA-256 `c4288e63b4c796d21458cd8130472f0f850ed889845fdd3f4fefb7e472a88027`).
  Logs: `.aguafria/precompile/single-library-html-{outcomes,blocks,inlines,build}.log`.
  This refresh does not change the outstanding AOT coverage figures below.

- Single-library AOT correction COMPLETED: removed the arbitrary 64-handler
  partition. One preparation now compiles all compatible candidates, including
  handlers already indexed in previous packs, into one source-compiled library.
  Incompatible configurations reject explicitly rather than silently splitting.
  Added a real 65-handler native regression that consolidates two prior packs
  and calls every exported handler. Final focused suite: **8 tests / 112
  assertions pass**, including restart and boundary checks. Full Learn run:
  **1,889 snapshots / exactly 1 AOT library**, 9,935,328 bytes plus 25,153,319
  separate debug bytes; **653.34 s** wall including JVM startup. All 1,889
  artifact indexes resolve to the same bundle. Fresh-JVM actual values bodies:
  **1,201.30 ms / 34 bundle hits / 1 bundle loaded / 0 compilations**, correct
  output. Coverage: **289 attempted / 287 analyzed / 2 load-failed / 1 explicitly
  ignored**, **2,115/2,706 operations prepared / 591 incomplete**. Report prefix
  `.aguafria/precompile/learn-single-library`; details and categories in
  `SINGLE_AOT_LIBRARY_2026-09-30.md`. Old cache libraries were not deleted while
  existing JVMs may still hold them; the new preparation's indexes are unified.

- Cross-image error identity repair: native argument passing and assignment now
  re-encode closed error sets by name, using Zig `@typeInfo` and `@field` in the
  receiving image. Numeric error-code input is rejected. Arrays, optionals and
  nested error-union payloads use compiler-bound codecs recursively. ABI version
  is now 2, so old native artifacts cannot satisfy the new calling contract.
  Fresh JVM with deliberately separate one-handler bundles: **1 test / 6
  assertions passed**, no runtime compilation. Values actual JVM bodies passed
  after re-preparation: **34 bundle hits / zero compilations**, 1,057.35 ms
  before the additional shared-writer boundary repair below.
- Shared writer boundary repaired too: shared support exports an opaque buffer
  handle and C-ABI success flags, not a foreign `std.Io.Writer` error-returning
  vtable. Adapter-local Writer callbacks construct local `error.WriteFailed`.
  Allocation failures return null/zero to the adapter's guarded panic path.
  Native failure injection, vector writes and zero-splat tests added.
  Final combined native run: **55 tests / 314 assertions pass**. Actual values
  body replay under final writer ABI: **1,815.21 ms / 34 bundle hits / 0 compiles**,
  correct named error and successful mutation. Translation cleanup also now
  retires newly required lesson dependencies without touching pre-existing user
  namespaces; targeted cleanup/exact-source regressions pass.
- Do not claim arbitrary cross-image transport is solved: open `anyerror` has
  no reflectable member list; named-error construction into it rejects explicitly.
  Error-bearing slices/unions/nominal aggregates without recipient-specific
  codecs are rejected rather than silently copied. Raw borrowed pointers and
  user callback error ABIs still require a separate provenance/aliasing audit.
  Full Learn preparation figures below predate ABI 2; only values has been
  re-prepared for this repair so far.
- Final documentation regeneration COMPLETED: **292/292** outcomes accepted
  (**288 upstream / 4 reviewed special cases**), **zero acceptance problems**.
  HTML preserves all **356 sections**, with **307 tabbed figures**, **202
  nonempty REPL transcripts**, and **1,362 matched inline references**. Browser
  layout tests **4/4** and highlighting tests **6/6** pass. The live localhost
  page shows Hello World and the correct named error in values' REPL output.

- User-requested clean rebuild after key improvements COMPLETED. Validated exact
  `/Users/pfeodrippe/.aguafria/zig` tree (27,935 entries, 15,138 files, no links or
  cross-device entries) and removed it: 1,051,772,152 logical bytes. No project
  caches or backup directories removed. Ran all Learn example namespaces via
  `clojure -X:precompile :source-dirs ["resources/learn/example"] :parallelism 4`.
  Separate report/log: `.aguafria/precompile/learn-key-v1-clean.edn` and `.log`.
  **959.77 s (15m 59.77s)** wall; **1,067,300 KiB allocated** before postchecks.
  **290 attempted / 288 analyzed / 2 load-failed; 2,115/2,708 operations prepared**,
  593 incomplete; **3,051 prepared handler records / 1,889 snapshots / 30 bundles**.
  Historical ABI-1 fresh-JVM values bodies: **34 bundle hits / 23 bundle loads /
  14 memory hits / zero compilations**, 3,765.84 ms. That correctness check FAILED:
  `error.BufferTooSmall` instead of `error.ExampleErrorVariant`. This is the same
  cross-image error-identity issue repaired above, not a successful correctness
  run. Retained here as historical evidence, not current repair status.
- Added generic `:ignore [namespace ...]` to explicit precompilation. Skips direct
  namespace/call/directory selections before loading, reports :ignored separately,
  and does not affect ordinary execution, docs, or transitive imports. Learn alias
  defaults to ignoring `learn.example.test-without-setEvalBranchQuota-builtin` at
  user's request. **3 tests / 15 assertions pass in a fresh JVM**. The completed
  clean run above predates this selection option; no evidence was retroactively
  relabeled. HTML/output regeneration underway with current compiler fingerprints.

- Artifact key improvements complete: canonical map/set encoding, full SHA-256,
  explicit key/native-ABI versions; no incidental file timestamps. Relocatable
  object inputs are content-identified, including per-module arguments, while
  location-sensitive source/library/archive paths remain significant. Applied to
  ordinary native artifacts and bundle indexes/packs, with no old-key fallback.
  **12 tests / 68 assertions pass** across artifact, native-linker, cache and bundle
  suites: printer settings/order, same-size/same-mtime byte replacement, relocated
  objects, changed code, compiler settings, ABI versions and fresh-JVM bundle reuse.
  Runtime regressions also pass: **32 tests / 156 assertions**; combined verification
  **44 tests / 224 assertions, zero failures/errors**. Formatting/diff checks pass.
  Native linker test confirms same library reused after touch/move; changing code
  produces a different library and native result (7 → 9). README documents scope.
  New key format requires one-time rebuilding/preparation; existing entries were
  not deleted. This is not a fix for the separate error-identity blocker below,
  and HTML has not been rebuilt for this focused key change.

- Removed all **6 defimport usages from Learn example files**: four parser
  dependencies now require `learn.example.error-union-parsing-u64`; two builtin
  imports now require `aguafria.builtin`. Also migrated two builtin snippets.
  All six namespaces load; native parser test passes; direct JVM parse returns
  `{:ok 1234}` and all four dependent `do-a-thing` calls pass. Isolated test cache,
  no whole-corpus rebuild or shared-cache reset. Added a regression preventing
  defimport in example files. Two placeholder raw-file imports remain only in
  the non-runnable `learn.snippet.style-example`; no fictional require targets
  were invented. HTML has not been regenerated for these changes.
- **New correctness blocker:** whole-Learn bundle cache fresh-JVM values check
  prints `error.DiskQuota` instead of `error.ExampleErrorVariant`. It has 34 bundle
  hits / 24 bundle loads / 14 memory hits / 0 compilations, 3,686.31 ms, but is
  NOT a correctness pass. Existing checker only asserts no compilations. Suspect
  error identity across separately linked packs; investigate generically and add
  an actual error-identity regression before calling this cache ready.
- Whole-Learn clean AOT measurement completed: **290 namespaces attempted**,
  **2,110/2,703 operations fully prepared**, **593 incomplete**, **3,046 prepared
  handler records**, **1,890 unique snapshots / 30 bundles**. Exit 0;
  **975.43 s wall time (16m 15s)**, **1.019 GiB allocated** shared cache.
  Cleared exactly the inspected shared cache (356 generated files) first.
  Report `.aguafria/precompile/learn-clean-bundled.edn`; full breakdown and
  two examples per gap category in `AOT_BUNDLED_LEARN_2026-09-30.md`.
  Two load rejections are deliberate invalid lessons; other gaps are NOT all
  harmless. Do not claim whole-corpus warming or redo this sweep without reason.
- User questions answered: generated source/metadata directories remain after
  packing. This clean run has **zero individual generated-handler dylibs**;
  456 standalone libraries are ordinary module/support snapshots. `defimport`
  is unnecessary for the translated parseU64 dependency; normal `:require` can
  replace it. No example/compiler edits made during this measurement.
- User requests clean starts: clear exactly `/Users/pfeodrippe/.aguafria/zig`
  before final clean preparation/verification; inspect targets and symlinks first.
  Do not touch project-local caches or treat a running old REPL as a fresh JVM.
- Bundle integration complete through normal production paths. Regression suite:
  **7 tests / 49 assertions, 0 failures/errors**, including fresh-JVM bundle reuse,
  runtime miss → standalone reuse, native panic containment, mode invalidation,
  error-union preparation and relative cache paths. Separate discovery tests:
  **2 tests / 27 assertions pass**. cljfmt and diff checks pass.
- Removed the inspected shared cache (270 generated files; project caches and
  earlier backup untouched). Clean default-cache values AOT: **27/27 operations,
  40 handler records, 1/1 function, 0 gaps**, **57 handler snapshots / 1 bundle**,
  39,543.77 ms. Only **3 dylibs** remain: bundle, shared support and user module.
  Fresh JVM: all **5 actual values body forms** produced expected output,
  **34 bundle hits, 1 bundle load, 14 memory hits, 0 compilations**, 1,208.94 ms
  excluding JVM startup. Cache **12.86 MiB** allocated. Restart user REPL to test.
  Values-only iteration complete; no full-Learn/HTML rebuild claimed.
- Bundle size measurement completed (isolated prototype, no production loader
  change). All **57 generated values handler snapshots / 530 exports** combined
  into one real ReleaseSafe dylib, same error traces/unwind/DWARF. Libraries +
  debug: **9.92 → 1.81 MiB (81.8% smaller)**; including verbose prototype manifest:
  **2.09 MiB (79.0% smaller)**. Shared support/user module unchanged. Five actual
  body forms pass through bundle, all 530 exports present, **0 compilations,
  34 disk hits, 14 memory hits**, 34 snapshots served by one bundle image opening.
  Warm evaluation 1,102.85 ms, loading/binding 50.28 ms; no whole-evaluation
  speedup claimed. Compile already prepared sources 2,820.20 ms + DWARF43.55 ms.
  `.tmp/bundle-bench-YtobIc` holds scripts/artifacts; full details in values report.
  Historical prototype results; production integration is tracked above.
- ReleaseSmall comparison completed on values only in isolated caches:
  both profiles **27/27 operations, 40 handlers, 1/1 function**, no gaps;
  five body forms pass in two fresh JVMs per profile with **0 compilations,
  34 disk hits, 14 memory hits**. Safe/Small preparation 31.81/27.41 s;
  cache 17.11/11.69 MiB; dylibs 6.57/5.87 MiB. First evaluations 4.550/4.559 s,
  second fresh-JVM evaluations 1.046/1.030 s (OS warm). No meaningful runtime
  speedup demonstrated. **Keep ReleaseSafe**: Small disables default safety;
  wrapper-local safety restoration does not protect arbitrary imported callees.
  No production/cache-default changes made. Details and reproduction in
  `VALUES_SHARED_CACHE_2026-09-30.md` (ReleaseSmall investigation).
- Production aggregation now uses immutable explicit-AOT packs with handler-key
  indexes and individual artifacts for later REPL misses. Snapshot ABI exports
  are isolated without coalescing native state. Warm library opening alone measured
  only 13.6–14.7 ms for 34 distinct libraries; no unmeasured bundle speedup claimed.
- Previous iteration was values-only; the latest request explicitly expands
  preparation to all Learn examples. HTML regeneration remains outside this run.
- Compact shared-support implementation complete. Shared result writers,
  page allocator and release functions now accompany the existing panic/guard
  support. Common support and generated JVM adapters use ReleaseSafe; adapter
  error tracing/unwind data are explicitly enabled, debug symbols retained.
  Ordinary user-module `:optimize` remains unchanged. New `:jvm-optimize`
  accepts Debug/ReleaseSafe only. No compiler-rt export workaround retained:
  pinned Zig's built-in compiler/sanitizer routines remain linker-managed.
- Replaced active `~/.aguafria/zig` after tests, preserving the old directory
  at `~/.aguafria/zig-before-shared-support-20260930` (recoverable; still occupies
  disk space, 284 MiB when measured after rotation). Prepared values only in
  the clean default cache: **27/27 operations, 40 handlers, 1/1 function,
  zero gaps**, 37,915.76 ms. New active footprint **17.70 MiB** allocated:
  6.70 MiB libraries, 9.55 MiB DWARF, 1.45 MiB other. Fresh default-cache JVM:
  all five body forms correct, **zero compilations, 34 disk hits, 14 memory
  hits**, 4,643.67 ms. Restart user REPL before testing updated runtime.
- Verification for compact support: 2 tests / **20 assertions pass** for
  checked optimization modes and cross-adapter mutable storage/lifetimes;
  PanicSmoke passes assertion, explicit panic, overflow and native deftest
  containment with mapped Clojure source. Zig formatting and diff checks pass.
  Isolated preparation/execution also passed before rotating the real cache.
  Do not repeat these tests without a new relevant change. No cross-platform
  verification or whole-Learn sweep claimed. Details in the values cache report.
- Shared native cache default implemented: `~/.aguafria/zig` in runtime,
  converter helpers and extracted project assets. Explicit `:cache-dir` and
  `aguafria.cache-dir` overrides remain. Old project caches untouched. Reports
  such as `container-lessons.edn` remain project-local diagnostics, not keys.
- Historical pre-compact shared preparation: **27/27 operations, 40 prepared
  handler records, 1/1 function, zero gaps**, 78.02 seconds. Report:
  `.aguafria/precompile/values-shared-cache.edn`. Two fresh JVMs evaluated all
  five actual body forms (not native main), with correct output and **zero
  compilations, 34 disk hits, 14 memory hits** each. Learn cwd: 4,858.15 ms;
  repo-root cwd: 999.92 ms. Different OS page-cache warmth; not a speedup ratio.
  Cache at that earlier snapshot was 222 MiB including compiler-analysis support;
  the compact-support figures above supersede it and the report's current run.
- Fixed the user's three error-union cache misses generically: finite error-set
  literal conversions prepared from compiler members; native error-set/union
  reflection retains structural schemas so TypeOf/print adapter keys match.
  Independent empty-cache preparation/fresh-JVM regression: 1 test, 6 passing
  assertions; both error members reuse artifacts. Cache default/override:
  1 test, 2 passing assertions. Reproduction and storage decision:
  `VALUES_SHARED_CACHE_2026-09-30.md`.
- No SQLite/DuckDB dependency: keyed native artifacts already require files.
  Existing artifact publication retained; this iteration verifies sequential
  reuse across fresh JVMs and working directories, not simultaneous multi-JVM
  writers. Cross-process contention/eviction remains separate work.
- Completed current user request: whole Learn AOT preparation, fresh build JVM
  from `examples/learn`, all 290 namespaces under `resources/learn/example`,
  four bounded workers, normal `.aguafria/zig` disk cache. Exec 99012 exited 0.
  Report `.aguafria/precompile/learn-aot-2026-09-30.edn`: **2,110/2,703 operations
  prepared, 593 incomplete, 3,046 prepared handler records, 697.17 seconds**.
  This new whole sweep supersedes the incremental 2,049 estimate below.
  288 namespaces analyzed; `test-blocks` and `var-must-be-initialized` failed
  to load. 50 baseline compiler rejections include invalid/context-dependent
  reference examples; do not call these all regressions or all expected.
  User can now restart a JVM from `examples/learn` using the normal cache:
  `values` 27/27, `test-slice-bounds` 12/12, `test-vector` 35/35,
  `test-pointer-arithmetic` 32/32 operations prepared.
  Do not rerun AOT just because the conversation continues. This historical
  whole sweep was project-local; only values has now been prepared globally.
- User's latest priority: continue preparation work only when it delivers
  substantial JVM-evaluation performance gains. Do not pursue the earlier
  2,200/2,400/2,600 count milestones merely as a metric. Measure first evaluation
  after restart separately from already-loaded execution and JVM startup.
- Completed performance comparison: owned root REPL 52092, future
  `performance-comparison-v2`, three separate JVMs (cold execution, compile-only
  preparation, prepared execution). **First evaluation: 16,979.99 ms / 14 builds
  cold versus 2,173.62 ms / zero builds prepared (7.81× faster, 87.2% less time).**
  Both return the same checked array mutation/slicing/indexing and arithmetic
  results. Already-loaded evaluations: 15.93 ms and 15.13 ms respectively.
  Compile-only preparation: 90,999.13 ms / 82 builds, 34/35 fixture operations;
  native invocation forbidden. Startup is excluded from evaluation timings.
  See `PRECOMPILATION_PERFORMANCE_2026-09-30.md` for limitations and reproduction.
  The earlier `performance-comparison` failed while loading the harness and
  is not timing evidence. All measurement futures are now terminal.
- New function-value fixes: no automatic C-export convention on ordinary
  scalar source functions; JVM calls use separate existing C trampolines.
  Function signature maps are not recursively treated as value storage schemas.
  Failed preparation requests no longer contaminate later preparation in the
  same namespace. Focused evidence: 4 ABI/request-isolation assertions plus
  3 signature-storage assertions pass. Clean-source Learn preparation recovers
  three calls in `test_comptime_evaluation.clj` (16/27 prepared; 9 failed
  operations still require comptime-only value transport, 2 inspection gaps).
  Root REPL 52092 `clean-function-values` complete. Do not reuse the earlier
  polluted REPL's function-values-correction report as post-fix evidence.
- Completed bounded checks: nominal identity discovery adds 19 prepared
  operations (69→88/103 across five namespaces); contextual inspection adds
  three (`test-arrays` 70→72, `doc-comments` 0→1). Along with the three function
  ABI recoveries and one packed-field recovery, this is **26 recovered operations,
  an incremental 2,049/2,703 prepared, 654 incomplete**. This combines disjoint
  targeted results with the previous snapshot, not a fresh whole-project sweep.
  Runtime-shrExact's invalid probe is removed without claiming its dead branch
  was prepared; four peer-type comptime-pointer inspection failures remain.
  Packed fix is verified: 7/8 Learn
  operations prepared (+1), four JVM assertions, original body/native test pass.
  Failure classification is persisted in
  `PRECOMPILATION_FAILURE_CLASSIFICATION_2026-09-30.md` (40 failed operations,
  7 rejected probes, grounded in original Zig directives).

- Completed: `az/defunion`, 16 named declarations in 15 Learn files; native,
  converter, lint and original-outcome checks recorded below. Do not repeat
  this work or present it as a new accomplishment on continuation.
- Completed focused proof: runtime tuple preparation, 19/19 operations and
  29 handlers; preparation loads no native functions; restarted JVM builds
  zero adapters. Runtime regression results: four tests / 35 assertions and
  the runtime unit suite: 31 tests / 153 assertions. Reuse this evidence unless
  a relevant implementation/test change invalidates it.
- Completed: all-290 compile-only inventory, owned Learn REPL **52949**, future
  `runtime-tuple-inventory`, exec session **42612**, PID **88008**. Report:
  `.aguafria/precompile/learn-operations-runtime-tuples-complete.edn`.
  **2,023/2,703 prepared, 680 incomplete; 688.31 seconds, zero native loads.**
  Four operations newly prepared (two overflow tuple assignments, two empty
  slice construction operations); `build.clj` now exposes an invalid native
  empty-tuple variant for `StandardOptimizeOptionOptions`, net gain three.
  No signatures in this inventory contain nested representation alternatives,
  so recursive expansion does not require repeating the full inventory.
- Completed: recursive nested representation expansion and the five boolean
  cache misses. Zig's argument reflection now reports runtime bool plus its
  two literal alternatives; known comptime tuple fields keep their known value.
  Added the boolean constructor used by the call phase to its source fixture.
  Empty-cache proof: **22/22 operations, 50 handlers, zero baseline failures,
  zero native loads, zero restarted builds**, three assertions pass.
  Evidence: `.aguafria/precompile-tests/runtime-tuples-13756545412958763445/report.edn`,
  root REPL 52092 future `boolean-tuple-restart` (completed).
- Completed affected observation regressions: four tests / 100 assertions,
  root REPL 52092 future `boolean-observation-regressions`.
- Completed full boolean-domain inventory: owned REPL 52949, future
  `boolean-domain-inventory`, report `learn-operations-boolean-domain.edn`.
  It exposed a regression: **2,011/2,703**, 692 incomplete, 173.33 seconds,
  zero native loads. All 12 newly incomplete operations rejected boolean
  literal descriptors in the preparation validator (e.g. `values.clj`'s `!`
  and `test_union_method.clj`'s `!`), while ordinary JVM calls accepted them.
- Completed correction: preparation accepts boolean literal descriptors and
  feeds their values to the SAME operator planner used by direct JVM calls.
  Regression reproduced two errors and one failure before the change; after
  the change all five assertions pass, including zero builds for direct calls.
  Root REPL 52092 future `boolean-operator-after` completed; do not repeat it.
  Affected operator regressions also pass three tests / 26 assertions
  (`operator-runtime-regressions`, same REPL); formatting and diff checks pass.
- Completed targeted preparation: **11 namespaces, 159/189 operations**,
  10.50 seconds, native invocation forbidden. All **12/12** regressions recover.
  Report `.aguafria/precompile/learn-boolean-operator-correction.edn`, owned
  REPL 52949 future `boolean-operator-targeted` completed. Combined with the
  unchanged entries of the boolean-domain snapshot, coverage is **2,023/2,703**,
  680 incomplete, 2,959 prepared / 63 failed handler variants. This is an
  incremental result, NOT a new full run. No union verification was repeated.
- Completed computed builtin parameter correction: source literals keep the
  context of computed parameter types, while explicitly typed operands keep
  their types. Two tests / 14 assertions pass (`computed-builtin-after`, root
  REPL 52092). `test_comptime_invalid_error_code.clj` now reports Zig's intended
  invalid-code error rather than a spurious i64/u16 transport mismatch.
- Classification completed for the prior snapshot's **40 failed operations**:
  22 valid-source preparation defects, nine correct negative-example rejections,
  four wrong diagnostics on negative examples, four target mismatches, one
  syntax-only runtime instantiation. Next work should target reusable runtime
  handlers with demonstrable first-call benefit, not force invalid/dead code
  into successful coverage. Examples still needing work:
  `build.clj:10`'s native empty-tuple options and
  `test_comptime_evaluation.clj`'s comptime-only function storage. Do not rerun
  the union, tuple restart, boolean or computed-builtin checks unless a relevant
  source change invalidates their recorded evidence.
- Two namespace failures remain: `test_blocks.clj` and
  `var_must_be_initialized.clj`. The reused-REPL sweep reports no registered
  declarations instead of their original load errors; this is not a fix.
- Pending afterward: other coverage categories and full HTML regeneration.
- Verification rule: record the change that necessitates each repeated test.
  A continuation alone is not an invalidation. Poll live work for completion,
  but avoid repetitive progress-only reports and do not rerun completed work.

## Implementation and verification record

- [x] Add `az/defunion`, matching `az/defstruct`'s named declaration shape.
      Support one member vector, docs, tagged/untagged unions, explicit tag
      types, layouts, nested methods, and native JVM constructors/reflection.
      Migrated 16 declarations in 15 Learn files without renaming identifiers
      or rewriting comments; anonymous local unions remain `az/union`.
      Added converter output and clj-kondo support. Preserve explicit union
      attributes when compacting generated declarations. Four focused tests /
      22 assertions pass; converter checks pass two tests / 18 assertions;
      emitter and lint suites pass 69 tests / 378 assertions. Direct Learn
      calls pass all 11 native tests in the ten positive union namespaces.
      Rechecked the named-union API (12 assertions, zero failures/errors) and
      `Number`'s native `anonymous-union-literal-syntax` test on September 30;
      both pass through the normal owned REPLs.
  - [x] Regenerate transcripts/outcomes for all 15 migrated files: 15/15 match
        original Zig, including five intentional-error examples. The initial
        transcript capture refused to overwrite the ten namespaces loaded for
        direct JVM checks; removing only those owned verification namespaces
        allowed normal capture to succeed. No guard was bypassed.
  - [ ] Regenerate and verify the entire HTML after the remaining shared-code
        work; do not publish a partial rebuild that drops stale transcripts.
  - [ ] Broad API/converter suite is not green. Recorded run: 119 tests,
        722 passing assertions, 15 failures, four errors. Union conversion
        assertions and enum-attribute handling were fixed afterward. Other
        observed failures include raw-number expectations versus ZigValue
        (`absolute -42`, `absolute 7`), unprepared fresh-process imports
        (TigerBeetle main/options), and old converted syntax/source ordering
        (TigerBeetle nested `for`, container fixture's forward `Replica`).
        Keep these separate from the passing union regressions; do not call
        that broad run successful or report a guessed post-fix total.

- [ ] Include numeric progress and remaining-error categories in every update:
      state the last completed inventory, verified delta, current focused test
      result, and distinguish unique operations from handler variants. Never
      count an in-progress fix as additional fully prepared coverage. Include
      two concrete source examples for every remaining category in status reports.

- [x] Add `az/explain!` (no `az/explain` alias): execute wrapped forms normally
      exactly once, preserve results/exceptions, and report actual native
      compilation, disk-cache and loaded-code reuse to stdout. Verify ordinary
      side effects, declarations, failures, and cold/warm native calls.
      Six tests / 20 assertions pass, covering return/exception identity,
      exactly-once side effects, disconnected output, async execution, and real
      compiled/disk-cache/loaded-code events. The kondo wrapper test also passes.

- [ ] Automatically discover JVM handler requirements across every Learn
      namespace using Zig compiler observations linked to emitter operations.
      Never infer native types from Clojure or execute example/test/comment
      bodies for discovery. Preserve unresolved specializations, unsupported
      storage placements and expected compiler failures in a persisted report.
      Compile supported signatures through the existing handler/cache path;
      verify normal JVM calls and subsequent JVMs reuse those artifacts.
      The initial compiler-backed discovery and bounded namespace sweep are
      implemented; remaining coverage gaps must not be counted as warmed.
  - [x] Verify why discovery touches the emitter: optional inspection hooks
        preserve lexical scope and assignable field/index/dereference locations;
        normal emission leaves them disabled. Added an unchanged-emission
        regression; the emitter suite passes 56 tests / 331 assertions.
  - [ ] Finish anonymous runtime tuple storage and its preparation path.
        Native overflow tuples retain storage through copying, assignment,
        and address-taking. Fixed snapshot-based sequence destructuring losing
        element types: compiler-reported tuple lengths select native element
        views, retaining ownership and mutability. Three focused tests now pass
        21 assertions (previously one failure and three errors). The expanded
        tuple/value/nested-value run passes eight tests / 107 assertions,
        including mutable destructured elements and bounds. Compiler reflection
        now records structural runtime-tuple identities and required comptime
        indices. Live calls/preparation share the index and embedded-value
        constructor planners. The empty-cache two-JVM regression passes three
        assertions: zero native loads during preparation, zero builds during
        restarted overflow-tuple construction, reassignment and destructuring.
        The surrounding tuple/representation/native-storage regressions pass
        four tests / 116 assertions. The all-290 replacement sweep on owned
        Learn REPL 52561 was stopped after discovering additional tuple-import
        defects; that JVM exited with code 143. Its checkpoint directory
        (`learn-operations-runtime-tuples.edn.d`) is partial, not a new complete
        inventory. Do not update full coverage totals from focused tests.
  - [ ] Finish generic compound-tuple/imported-call preparation and restart
        coverage. Avoid native integer-width queries when a call has no untyped
        JVM integer needing a runtime carrier. The execution guard exposed this
        in `assign_undefined.clj` and `mutable_var.clj`; two focused tests / five
        assertions and integer-carrier regressions (two / 17) pass. Compound
        tuple elements now serialize as type expressions, not value vectors,
        in both compiler observations and JVM result transport. Targeted Learn
        preparation reaches 14/15 operations, all 18 handler records prepared,
        across assign-undefined, mutable-var and math-add; the remaining entry
        is inspection placement. Expanded empty-cache restart test passes:
        19/19 operations, 29 prepared handler records, no baseline failures,
        zero native loads during preparation and zero builds in the second JVM.
        It includes numeric tuples and a tuple containing a slice, changing
        runtime values between calls. Four runtime regressions / 35 assertions
        pass after nested type-schema canonicalization.
        The full runtime unit suite also passes 31 tests / 153 assertions.
        The restart test rejects missing observations as well as failed handlers;
        its initial fixture shadowed a parameter, which Zig rejected and the
        weaker check missed.
        Fresh full inventory completed on owned Learn REPL 52949 (four workers),
        report `learn-operations-runtime-tuples-complete.edn`: 2,023/2,703,
        680 incomplete, zero native loads; see resume checkpoint.
  - [ ] Recursively expand compiler-observed representation alternatives inside
        tuple alternatives. New regression reproduces a nested `:representations`
        descriptor escaping expansion and being treated as an unsupported type.
        Keep the representation budget explicit; do not drop variants silently.
        Recursive expansion implemented; four tests / 18 assertions pass.
        Expanded native nested-tuple restart proof initially failed its zero-build
        check with five builds; the boolean-domain fix below closes these misses.
        The completed Learn sweep used
        pre-fix loaded discovery code, but inspecting its signatures found
        zero nested alternatives; no Learn namespace needs reanalysis for this
        expansion-only change.
        The first expanded fixture observed/prepared the nested operators but
        additionally took the address of a mixed runtime/comptime tuple; its
        nominal anonymous type is currently unrepresentable (21/22 operations
        prepared). Keep that separate gap open. The operator restart fixture
        now discards its result instead of adding unrelated address-taking.
  - [x] Prepare JVM literal boolean alternatives for compiler-observed runtime
        bool operands. Zig establishes the finite domain; no Clojure type
        inference or native execution. Preserve known boolean tuple constants
        without widening their value. The boolean constructor is now explicit
        in the restart fixture, matching the evaluated call phase. Empty-cache
        proof: 22/22 operations and 50 handlers; no native loads in preparation,
        no builds in the restarted JVM; both false/true nested tuples verified.
  - [ ] Preserve exact anonymous tuple identity when taking the address of a
        tuple with both runtime and comptime fields. Reproducer: a runtime bool
        tuple `inner = .{flag} ++ .{}` followed by `outer = .{inner} ++ .{false}`
        and `&outer`. Compiler observation currently yields `[nil [:*const nil]]`.
        Do not replace this nominal type with a structurally guessed runtime tuple.
  - [x] Report unique incomplete-operation groups separately from handler
        variants. Two regression tests / 11 assertions pass; the last completed
        inventory's ten groups sum exactly to 683. Preserve multi-reason cases
        and observed entries without handlers instead of hiding them.
  - [x] Keep precompilation namespace-generic (`:analyze`, or classpath-backed
        `:source-dirs`), separate from `:prepare`. Preserve compiler-confirmed
        named tuple identities, numbered field layout and JVM sequence encoding;
        canonicalize constructor/conversion keys before hashing. The focused
        tuple/private-constructor checks pass 17 assertions, mixed-field tuple
        storage passes four, and an empty-cache two-JVM tuple test passes three
        (zero loads during preparation, zero builds during restarted calls).
        Runtime/emitter/value/nested-value regressions: 91 tests / 552 assertions.
        Explicit precompilation regressions: seven tests / 35 assertions.
        Updated all-290 inventory: `.aguafria/precompile/learn-operations-named-tuples.edn`.
        2,020/2,703 operations fully prepared, 46 failed handler records,
        683 incomplete operations; 218.11 seconds with existing caches and
        zero native loads. Remaining anonymous overflow tuple storage is not
        covered by the declared-tuple fix.
  - [x] Finish private-member preparation/restart verification. Member lookup
        and invocation now use the explicit type's defining module without
        making private methods public. Zig decides whether a member is a
        method. The enum/union/mutable-struct fixture prepares all 18 records;
        its same-JVM regression passed 9 assertions in a fresh REPL. A stricter
        empty-cache two-JVM test exposed two misses (enum-literal construction
        and noncanonical type metadata in field adapter keys). Both paths have
        been corrected. Three planner-only tests / six assertions pass for
        canonical type identity, native member spelling, and literal-vs-runtime
        constructor selection, with compilation/invocation disabled. The
        empty-cache rerun found one remaining nil-vs-absent source-order key
        mismatch. After normalizing that metadata, the two-JVM test passes:
        45 preparation builds, zero native loads during preparation, and zero
        new builds during calls in the second JVM. The full runtime unit suite
        passes 30 tests / 149 assertions. A targeted Learn retest prepares all
        12 previously failing private-member operations with zero native loads.
  - [x] Finish the existing all-290 comptime-value sweep. After the user freed
        disk space, the same JVM resumed from 210 checkpoints and completed all
        290 attempts: 2,005/2,703 operations fully prepared, 63 failed handler
        records (previously 75). The consistent baseline predates the private
        member fixes; do not present targeted results as a completed recount.
  - [x] Verify private generic type constructors and their field views with
        empty-cache restart coverage, preserving the type factory's privacy.
        The expanded two-JVM test passes three assertions; the private-type
        fixture passes six, including real field access and zero new builds.
        Normalize reference-role metadata by its explicit logical identity
        for stable adapter keys. Runtime/emitter tests: 86 / 472 assertions.
        Two Learn computed-type constructors now compile instead of failing
        on private `List`, but remain partial for native-value-only inputs.
  - [x] Complete the updated all-290 owner-scope inventory on the fresh owned
        Learn REPL (51029), using four bounded workers and no native loading.
        Report: `.aguafria/precompile/learn-operations-owner-scopes.edn`.
        191.65 seconds with existing caches: 2,019/2,703 operations fully
        prepared, 47 failed handler records, 32 partial. Overall discovery is
        still incomplete; do not count the remaining 684 operations as warmed.
  - [x] Complete the broader 12-fixture restart verification with the final cache
        identity normalization. Its cold preparation hit the 300-second test
        deadline after 11 checkpoints; no second-JVM call phase ran. Retain that
        cache was continued compile-only in another JVM (six remaining builds,
        zero native loads). A separate invocation JVM loaded 65 modules and
        built zero artifacts. This is a resumed preparation proof, not a claim
        that the original timed-out test passed. Allow 600 seconds for the cold
        preparation phase; invocation retains its 300-second deadline.
  - [x] Use compile-only Zig reflection with baseline/probe diagnostics; isolate
        failing probes instead of abandoning the remaining operations in a file.
  - [x] Preserve field/index lvalues and prepare their native view handlers;
        include compound assignments and result decoding in restart tests.
  - [x] Prepare disk artifacts without loading native generations. The broader
        scan exposed macOS TLS-key exhaustion in the previous materialization
        path; compile-only preparation now rejects native loading/invocation.
  - [x] Include address-taking and static/runtime slicing in the shared
        preparation paths; verify runtime slicing/mutation after a JVM restart.
  - [x] Persist explicit coverage totals. Observed signatures, partial handlers,
        unsupported operations and failed namespace loads never count as fully
        prepared operations.
  - [x] Reverify the latest initializer/null and nominal-type additions. After
        authorized shared-cache cleanup, the recovered sweep attempted all 290
        namespaces. The newer nominal sweep prepared 3,515 handler records
        (not unique libraries), with 1,482 / 2,583 emitted operations fully
        prepared. The expanded restart test now passes: preparation loads zero
        native modules; the second JVM runs covered operations with zero builds.
  - [x] Add compiler-observed tuple/comptime-argument preparation and its
        restart test. Live JVM print calls (including escaped/Unicode formats)
        now reuse prepared adapters without compiler invocations. Include both
        envelope and native-payload cleanup handlers; the latter was missing
        from the previous restart preparation and caused three extra builds.
        Preserve typed tuple elements even when Zig constant-folds them.
        Normalize equivalent type expressions through the shared call planner;
        preparation and ordinary calls must generate identical cache keys.
        The fresh-JVM test passes for numeric/boolean tuples, escaped/Unicode
        formats, type comparisons and explicit native-result cleanup. Broader
        tuple shapes and named-type coverage remain in the full-sweep work.
  - [ ] Complete remaining nominal/context-dependent/syntax adapter coverage,
        and verify every prepared family against the normal JVM call path.
        Keep unsupported cases explicit; do not substitute Clojure type guesses
        or run example bodies to discover missing signatures.
    - [x] Identify explicitly referenced imported containers by Zig type equality
          against their real import references. Qualify adapter parameter schemas
          before hashing so compiler-discovered and live imported types share
          cache identities. The SemanticVersion live regression passes all nine
          assertions, including field access/address-taking with zero new builds.
          The broader discovery regression passes 18 tests / 212 assertions.
          The expanded fresh-JVM regression also passes: preparation loads zero
          native modules; the second JVM makes all covered calls with zero builds.
    - [x] Inventory actual member invocations as well as field lookups, and
          share their adapter planner with ordinary JVM calls. Verify static
          functions and const/mutable instance receivers without executing
          the discovery test body. Do not count private-member gaps as prepared.
          The focused suite passes three tests / 28 assertions. Fix probe
          placement to preserve dot-method lookup and receiver lvalues;
          static function lookup returns a callable closure, not an attempted
          serialization of a storage-free Zig function. The expanded restart
          check passes all three assertions (zero preparation loads and zero
          second-JVM builds). Whole-Learn verification is still running in
          `learn-operations-members.edn`; private-member gaps remain explicit.
          The broader discovery/member regression passes 21 tests / 236 assertions.
    - [x] Use an inspection runner that does not collide with a lesson's
          exported C `main`, while preserving test analysis and never executing
          the tests. The isolated Zig check and three native frontend regression
          tests / 31 assertions pass. The members inventory predates this runner
          change. The real `libc_export_entry_point` baseline now passes.
    - [x] Preserve slice-index result context using Zig's own receiver slice
          length type in the inspection probe. Seven focused assertions pass;
          the real exported-entry lesson now has four observed operations,
          three prepared and one explicitly unsupported standalone `intCast`
          without a destination type. No probe errors remain in that lesson.
    - [x] Inventory original top-level callables even when no declaration calls
          them. Prepare scalar result readers as part of single-function
          preparation; exclude internal adapters to avoid recursive preparation.
          Six assertions verify unused-function coverage, explicit generic/test
          skips, no discovery execution, and a normal call with zero new builds.
          The expanded fresh-JVM check passes: zero preparation loads and zero
          builds after restart, including an unused function call. The full
          callables inventory attempts all 290 namespaces and records 445
          callables separately: 166 prepared, 259 skipped, 20 failed.
    - [x] Bundle the pinned native parsing helper for four import-dependent
          lessons using ordinary project asset discovery. Keep hand-written
          declaration semantics via `:source-kind :aguafria`. Three asset tests
          / six assertions pass; a fresh Learn JVM compiles all four baselines
          and prepares their functions with zero native loads. Raw `defimport`
          member calls remain declaration-only and explicitly unsupported.
    - [x] Recognize nominal types returned by type functions using compiler-
          observed constant arguments and Zig type equality. Preserve exact
          qualified type expressions in ordinary JVM results and adapter keys.
          Three real lessons improve from 69 to 109 prepared operations before
          the subsequent literal-constructor correction. The generic fixture
          passes ten assertions and the expanded restart check passes: zero
          preparation loads, zero second-JVM builds, including three literal
          constructions of computed types. Nonliteral computed-type
          construction remains partial. The corrected full inventory prepares
          1,993/2,703 operations, with 710 incomplete and zero native loads.
          Nested type identities and other nominal/context gaps remain open.
    - [x] Fix the `noreturn` JVM bridge storage error; do not misclassify
          `panic_handler/myPanic` and `test_functions/abort` as intended compile
          failures. Both now prepare without execution. Use a void result in
          the guarded bridge, preserving noreturn on original Zig functions.
          Five restart assertions pass: zero preparation loads and zero builds
          for subsequent direct/indirect guarded panic calls and arithmetic.
          `_start` remains explicitly rejected by Zig's naked-call restriction.
          The completed 290-namespace recount records 1,994/2,703 operations
          prepared, 168 declared callables prepared and 18 failed, with zero
          native loads. All 709 incomplete operations remain explicit.
    - [ ] Transport comptime-only compound values without attempting native
          storage allocation. The compiler-backed sweep repeatedly rejects
          `typeInfo` results because `builtin.Type` has no runtime size.
          Use compiler-authoritative expressions/values, not JVM type inference,
          and verify the same ordinary call path plus compile-only preparation.
          Comptime aggregate results now retain their qualified producing Zig
          expression and a compiler-generated inspection view, without native
          value storage. Two JVM tests / 15 assertions cover reflection, nested
          fields, boolean truth semantics and user aggregates containing
          types/function bodies. All 12
          formerly failing typeInfo signatures across 11 Learn operations now
          prepare with zero native loads. Five preparation assertions and five
          fresh-JVM assertions pass, including zero builds on restart. Complete
          downstream comptime field/constructor discovery remains open; the
          previous whole-Learn totals have not been replaced by a partial retest.
    - [x] Preserve native debug information with persistent macOS artifacts.
          The broader panic test contains the panic but loses source locations
          when reusing an old image whose Mach-O debug map references a missing
          Zig temporary object. Full-debug artifacts now retain their DWARF
          independently and validate it on cache lookup. Eight unit assertions,
          three panic-smoke assertions and the six-test / 30-assertion preparation
          suite pass. The strengthened restart test also passes: zero native
          builds, with source-mapped direct/indirect noreturn panic exceptions.
    - [ ] Support compiler-derived imported-container layouts for construction
          from plain JVM maps. Until supported, report these constructor adapters
          as partial (`:external-type-layout`), not fully prepared. Existing
          native imported values can still use the prepared adapters.
  - [x] Verify C variadic calls through the ordinary call planner. Preserve
        compiler-observed string literals and explicitly typed native varargs;
        prepare C-alias result readers, and flush buffered C stdout before
        restoring REPL output capture. Discovery tests pass 13 tests / 92
        assertions including the fresh-JVM zero-build check. Output-capture
        regressions pass three tests / six assertions, including exceptions.
  - [x] Run the updated inventory in a fresh owned JVM: 290 namespaces attempted,
        zero native modules loaded, 1,834 / 2,620 emitted operations fully
        prepared in 347.88 seconds. Keep all 786 incomplete operations explicit.
        This snapshot predates the variadic and pointer-qualifier additions;
        it is not a claim of complete coverage.
  - [x] Verify compiler-derived pointer qualifier schemas in both discovery and
        live JVM result transport. The focused regression passes 17 assertions,
        preserving volatile/aligned/allow-zero/C/sentinel types. Eight actual
        Learn lessons prepare 54 / 64 operations, with the ten gaps retained.
        The restart check exposed map-order-dependent adapter keys and one
        missing string decoder. Fix both through canonical metadata-aware keys
        and the compiler-observed decoder type; the corrected fresh-JVM check
        passes with zero preparation loads and zero builds after restart.
        The subsequent clean owned REPL passed 28 tests / 217 assertions.
  - [x] Prepare tuple repetition/concatenation signatures, including raw literal
        and explicitly typed native representations of compiler-known boolean
        and string fields. The discovery suite passes 17 tests / 140 assertions
        including fresh-JVM zero-build reuse. The two actual Learn lessons
        initially prepared 30 / 34 operations without native execution. The
        four tuple field/index accessors now use the ordinary tuple-value planner;
        the targeted compile-only report prepares all 34 operations with zero
        native modules loaded. Keep any representation-expansion limit visible
        as partial coverage.
        The expanded live check now passes three tests / 97 assertions with
        zero builds for all 16 input representations and their accessors.
        Tuple results retain native numeric types; field-result writers match,
        and readers for compiler-provided structural schemas are shared.
        The expanded fresh-JVM check passes three assertions: zero native loads
        during preparation and zero builds in the second JVM. The broader
        regression check passed 29 tests / 315 assertions. The consistent full
        sweep `learn-operations-shared-readers.edn` completed all 290 namespaces
        in 1,165.81 seconds, loading zero native modules. It fully prepares
        1,898 / 2,620 emitted operations and preserves all 722 incomplete ones.
        This snapshot predates imported-container identity/layout-status fixes;
        the interrupted previous sweep is not a completed inventory.
  - [x] Verify compiler-observed pointer dereferences and optional
        unwrap preparation. Inventory `clojure.core/deref` as well as `az/deref`;
        keep dereference probes addressable and use the ordinary borrowed-view
        adapter. Live const reads, mutable writes and unwrap reuse pass, as does
        the expanded cold/fresh-JVM regression (zero native modules loaded by
        preparation; zero builds after restart). Current discovery suite:
        11 tests / 74 assertions passed. Do not count this as
        full Learn coverage: the all-namespace sweep still has explicit gaps.
  - [x] Verify dependent builtin parameters across a fresh JVM. The
        shared call planner now retains source literals for builtin signature
        placeholders such as `T` / `Log2T`, while preserving explicit native
        operand types. Exact shifts compile and reuse their handlers; an
        explicitly typed wider shift operand is correctly rejected by Zig.
        The unit suite passes 11 tests / 80 assertions and existing bridge
        regressions pass 11 tests / 102 assertions. The expanded restart test
        passes (three assertions): preparation loads zero native modules and
        normal calls after restart issue zero builds. Discovery suite total:
        12 tests / 83 assertions passed.
  - [x] Verify the expanded named-container/member restart regression. Discovery
        now includes callable `defconst` containers and type-level fields;
        enum lookup, union construction/field reads and shared container-variable
        mutation passed both live and fresh-JVM zero-build checks. The prepare
        JVM loaded zero native modules (two tests / 10 assertions passed).
        The updated all-290-namespace sweep fully prepared 1,694 / 2,583
        operations; 889 remain unprepared. The discovery suite passes eight
        tests / 52 assertions, including the restart test.
  - [x] Prepare storage-free enum/comptime-type operands and literal syntax
        calls through the normal JVM planner. Keep native structural type
        identity stable between compiler discovery and runtime reflection;
        verify a fresh JVM does not rebuild equivalent TypeOf signatures.
        The discovery suite passes 10 tests / 62 assertions, including zero
        native loads during preparation and zero new builds after restart.
        Existing JVM bridge regressions pass 11 tests / 102 assertions.
        Invalid UTF-8 cannot silently become replacement JVM characters.
  - [ ] Complete the new full inventory after the literal/structural-type
        changes. Six targeted lessons prepare 75 / 82 operations; the remaining
        seven concern container syntax and comptime-only Type reflection.
        The previous full inventory remains the latest completed baseline.

- [x] Fix the `values.clj` JVM regression without changing the lesson: ordinary
      numeric operator operands retain literal/comptime context rather than
      becoming synthetic i64/f64 runtime parameters. Keep explicitly typed
      native operands typed, addressable, and subject to Zig's narrowing rules.
      Add the exact nested i32/add regression, float/comptime and mixed-operand
      checks, and update obsolete raw-number coercion test expectations.
      The maintained Learn tests evaluate all 78 inventoried `values.clj` probes
      (73 contextual probes plus five literal schema/boolean forms), and compare
      the full direct JVM body's output with the compiled native entry point.
      Threading subforms now retain their incoming value during audit execution.
      Learn body/planner regressions: 17 tests, 120 assertions, no failures.
      Coercion suite: five tests, 55 assertions, no failures. Rebuilt the entire
      HTML after 288 upstream outcome matches plus four reviewed special cases.
- [x] Fix compound assignment operand typing generically: pointer offsets and
      shift amounts must not be coerced to the target's type. Evaluate the
      actual many-item-pointer and slice test bodies on the JVM, retain their
      source unchanged, and add source-body regression coverage to Learn tests.
      Mutable field reads now borrow the owner's native storage, including
      slice `:ptr` and `:len`; Zig supplies layout, type and constness. Keep
      boolean/optional reads compatible with Clojure false/nil semantics.
      Seven real source test bodies pass; 14 body/planner tests (35 assertions)
      pass. Compound/field/unsigned regressions pass (19 assertions), as do
      field-accessor (21), false/nil keyword-field (8), and ZLS/HTML (15) checks.
      Full HTML regeneration verified 288 upstream comparisons plus four
      reviewed cases; the served file matches disk and has 202 REPL panels.
- [x] Audit all Learn examples again with the current compiler, including
      test bodies, local initializers/values and contextual subexpressions.
      Reuse bounded, isolated workers with checkpoints. Record every failure,
      expected-error example and context/argument/target exclusion in a new
      dated Markdown report; never count exclusions as successful evaluations.
      Completed all 290 files / 9,236 cases with four workers in 877 seconds.
      See `JVM_AUDIT_2026-09-29.md`: 3,236 non-error completions, 1,627 exceptions,
      62 returned error values, one worker exit, and 4,310 context/load/target
      exclusions. Pointer arithmetic has 77 passing checks; single-item pointers
      have 90 plus nine context exclusions; vectors have 88 plus eight exclusions.
      These are probe counts, not independent defects or exhaustive semantic
      proofs. A returned error union is never counted as a passing assertion.
- [ ] Triage and fix the remaining JVM audit failures separately from expected
      upstream failures and repeated-state/probe-context artifacts. Observed
      remaining gaps include result-type-dependent builtins in
      `test_pointer_casting.clj`, and callable type members in
      `test_structs.clj`. Do not claim all JVM expressions work yet.

- [x] Fix direct JVM evaluation of `pointer-array-access`: indexed native
      elements must borrow the original storage, preserving Zig's element type
      and pointee constness. Verify mutation through the pointer updates the
      parent, const writes fail, and nested arrays/slices/sentinels still work.
      Also derive slice-storage pointer types through Zig `@TypeOf`, never by
      searching type text for `const`. All three original pointer test bodies
      pass as ordinary JVM forms. Seven JVM tests / 60 assertions and 28 runtime
      tests / 138 assertions pass. No Learn example was changed for this fix.
      Regenerated and verified 288 upstream outcomes plus four reviewed cases;
      the served HTML matches the rebuilt file, includes 202 REPL transcripts,
      and contains the successful `pointer-array-access` native test output.

- [x] Remove Clojure-side type inference from hover reports. Source traversal
      only supplies spans; type contents must come from Zig compiler or ZLS.
- [x] Remove browser `title` attributes from custom type tooltip spans.
- [x] Connect ZLS 0.16 to generated Zig via exact emitter source mappings;
      verify pointer bindings, struct methods/destructuring, rebuild and inspect.
      Verified `x: i32`, `x-ptr: *const i32`, `y-ptr: *i32`, Vec3 locals and
      destructured f32 fields using actual ZLS responses. No Clojure inference.
      7 source-map/report tests (106 assertions), 55 emitter tests (319
      assertions), 71 Learn tests (11,381 assertions) pass. Full regeneration:
      288 upstream comparisons + 4 reviewed cases; 202 REPL transcripts.
      Browser keyboard inspection displays one custom tooltip, no native titles.
- [ ] Extend exact emission mappings beyond the currently mapped identifiers
      and compiler debug probes. Unmapped forms and uninstantiated generics
      must stay unavailable unless a Zig tool supplies their result.
- [ ] Investigate long-lived JVM native-library TLS exhaustion on macOS
      (DYLD pthread-key allocation failure observed during the broad JVM suite).
      Targeted pointer/value checks pass; do not claim this lifetime issue fixed.
      Reproduced during the 2026-09-28 full Learn regeneration after running
      the indexing regression suite in the same JVM: diagnostic
      `java-2026-09-28-225250.ips`, DYLD "could not create thread local variables
      pthread key". Resume verified outcome caches in a fresh build REPL;
      that recovery does not fix the native-library lifetime limit.

- [ ] Preserve native identity for JVM numeric constructors, operations and
      function results; extract plain values explicitly with `az/value`.
      Keep predicates as JVM booleans so `false` remains false in Clojure.

- [ ] Fix JVM argument transport for typed borrowed `ZigPointer` values;
      evaluate both `address-of-syntax` let forms and add transport regressions.
- [ ] Verify the whole `conversion-between-vectors-arrays-and-slices` let on
      the JVM. Preserve array/slice backing owners and dereference pointer-to-array
      results before coercing to vectors; no addresses into temporary arguments.

- [ ] Add `az/vector`, inferring lane count with equivalent JVM/native behavior;
      audit all Learn constructors and simplify eligible ones without changing
      original names/comments. Preserve type schemas and array/slice coercions.
- [ ] Add identity `az/debug!` type probes with source locations, structured
      REPL reports and atomic `.aguafria/debug/types.edn` output. Keep inspection
      separate from executable code; test single evaluation and file-only mode.
- [ ] Regenerate real Learn outputs and HTML after these compiler/API changes.
- [ ] Future whole-form/editor analysis: query generated Zig through ZLS and
      separately labeled compiler-confirmed observations; no Clojure inference.
      Do not treat ZIR/ast-check as fully typed IR or claim unused generic
      specializations were analyzed. Reuse structured reports for nREPL/LSP;
      no editor-specific changes or new language server required for debug!.

- [x] Automatically cljfmt modified Learn examples before HTML builds and
      translation/output verification. Cover staged, unstaged and untracked
      files; stop on errors; fingerprint only after formatting. Test integration
      and regenerate verified HTML. Verified 71 tests / 12,532 assertions,
      all 292 example outcomes, 351 authored inline checks, and served HTML.

- [x] Restore original identifier, test-title, and comment wording in 16
      vector/array/destructuring/loop examples, including `test_vector.clj`.
      Add 48 source-fidelity assertions to the Learn test suite; output equality
      alone does not establish faithful translation of names or comments.
- [x] Screen all 290 authored examples against their pinned Zig sources;
      record the findings and limitations in `SOURCE_FIDELITY_AUDIT.md`.
- [x] Manually review the remaining 210 source-fidelity candidates and restore
      unnecessary rewrites. Candidates are not all confirmed bugs: account for
      required imports, invalid-source examples, and Clojure spelling changes.
      Keep original comments verbatim apart from comment delimiters; preserve
      identifier wording instead of inventing descriptive replacement names.
      Completed four disjoint manual-review batches covering all 290 authored
      examples, including unflagged files. Expanded regression checks to all
      files and inline/doc comments: 1,166 assertions pass. No unexplained
      screening findings remain; necessary adaptations are documented in
      `SOURCE_FIDELITY_AUDIT.md`.
- [x] Rebuild and verify all genuine comment-form REPL outputs and HTML after
      the corpus-wide fidelity cleanup; run the Learn suite and inspect the
      served page before reporting completion.
      All 292 outcome checks pass (288 upstream comparisons, four documented
      special cases). Rebuilt 202 REPL transcripts and 307 tabbed figures;
      351 authored inline checks and all fragment checks pass. Learn suite:
      68 tests / 12,521 assertions, zero failures/errors. Served HTML equals
      the verified disk artifact; refreshed the browser and opened the vector
      tab to check restored names/comments and both real test results.
- [x] Keep diagnostic forms in build records valid EDN: pprint's reader
      abbreviations such as `#'x` are not EDN. Add a round-trip regression test.

- [x] Regenerate all Learn outcomes and HTML after the `ZigValue` indexed-access
      fix, verify the served page, and refresh the browser. Runtime tests alone
      do not complete a Learn change: rebuild its real REPL outputs and HTML
      before reporting completion. All 292 outcome checks and 351 authored
      inline checks passed; rebuilt 202 REPL transcripts and verified the
      destructuring example's output in the refreshed browser.

- [x] Implement Clojure indexed access on `ZigValue` so JVM vector
      destructuring works directly on native arrays; verify the example's
      `destructuring-arrays` body and native swizzle call, bounds, rest, nested
      arrays, mutation, and closed-value checks. Verified 11 tests / 124
      assertions and the exact body evaluated in its Learn namespace through
      nREPL. Existing JVMs need a restart for the changed native-handle class.

- [x] Verify clj-kondo accepts both `az/array` arities from its actual source
      definition; refresh stale two-argument analysis without a duplicate hook
      signature or disabling invalid-arity checks. All 11 editor-hook tests /
      45 assertions pass; sentinel example lints with zero warnings or errors.

- [x] Add `(az/array elements {:sentinel value} element-type)` for native and
      JVM construction; update the converter and sentinel examples while
      preserving upstream comments and names. Format, test, and regenerate HTML.
- [x] Use `[:array length {:sentinel value} element-type]` for sentinel types;
      remove the separate legacy type tag from emission, conversion, and JVM
      storage handling, with explicit rejection tests and no compatibility path.
      Verified 8 focused tests / 203 assertions, 54 emitter tests / 313 assertions,
      and 10 editor-hook tests / 42 assertions. All 292 Learn outcome checks and
      351 inline checks passed; regenerated 202 REPL transcripts and HTML.
- [ ] Preserve intermediate native array type information in JVM `az/get-in`:
      an inner sentinel array currently decodes to a Clojure vector before the
      next index, losing its sentinel slot. Native access in an enclosing
      Aguafria form preserves it; this is separate from array construction.

- [x] Restore sentinel-array test labels using only necessary Clojure-symbol
      adaptations, original local identifiers, and verbatim explanatory comments;
      format and verify regenerated REPL output. Do not invent replacement
      test descriptions or paraphrase upstream comments in future translations.
      Verified both direct comment-form test calls, upstream outcome match,
      cljfmt check, and regenerated/served HTML (292 accepted example outcomes).

- [x] Restore all four explanatory comments from the upstream multidimensional
      array example. Preserve upstream instructional comments when editing
      translations; adapt syntax/identifier references only when necessary.

- [x] Add shared native destructuring for binding positions; verify typed
      arguments, loops, nested patterns, aliases, and reference semantics.
      Verified native/JVM regression coverage, including source evaluation
      once and pointer aliases. Native fields remain strict; `:or` is rejected.
- [x] Add cljfmt once to Learn tooling, format modified examples before the
      build, and regenerate/verify the HTML and real REPL transcripts.
      Modified examples pass cljfmt. All 292 outcome checks pass (288 upstream
      matches and four reviewed special cases), with 202 REPL transcripts.
      Served HTML includes the formatted destructuring examples and outputs.

- [x] Support keyword field access (`(:x point)`) on JVM native values/types and
      in native declarations, using the same field-access implementation.
- [x] Audit all Learn examples, snippets, and inline examples; migrate field
      and index expressions to keyword lookup, `az/get`, and `az/get-in`, then
      verify original Zig equivalence and regenerate actual REPL transcripts.
      Migrated 94 examples, two snippets, and four inline catalogs. Explicit
      Zig field-builtin examples and API reference mentions remain intentional.

- [x] Add `az/get` and `az/get-in` for native field/index access; use paths in the array
      examples and verify native emission, JVM access, and refreshed outputs.

- [x] Remove obsolete API-name cleanup and startup namespace repairs from
      `zig.clj`; define custom syntax functions/macros directly, preserve Var
      identity on reload, and verify JVM calls, metadata and regenerated docs.
      Verified 2026-09-26: emitter, keyword, and hook suites passed (64 tests /
      1,017 assertions), plus focused JVM/value checks including keyword lookup,
      nested access, false/nil fields, container constants, and reload identity.
      All 292 outcome checks passed; 202 real REPL transcripts regenerated;
      all 351 inline checks passed. Acceptance and original-document/output
      tests passed (3 tests / 21 assertions). Reloaded the served page and
      confirmed keyword/get/get-in syntax with passing array-test transcripts.

- [x] Replace `az/labeled-block` with `az/with-block` and keyword labels,
      migrate authored examples/converter, verify JVM/native block values and
      lexical captures, then regenerate the HTML and real REPL outputs.
      Verified 2026-09-26: 61 focused tests / 338 assertions passed; all 292
      outcome checks passed, with 202 REPL transcripts regenerated. Acceptance
      and original-document/output tests passed (3 tests / 21 assertions),
      as did 6 highlighting tests. Reloaded the served page and confirmed the
      keyword-labeled initializer and its real passing native test transcript.
- [x] Fix clj-kondo's false unused-binding warning in the `fancy-array`
      `with-block`/pointer-capture loop; retain genuine unused-local warnings.
      Hook analysis now resolves eagerly within clj-kondo's dynamic context.
      Verified the actual `test_arrays.clj`: zero errors and zero warnings;
      all 9 hook tests / 41 assertions passed.

- [x] Replace `az/array-init` with `(az/array elements element-type)`, inferring
      length; preserve explicit lengths, vectors and sentinels with `az/init`.
      Migrated source callers, Learn examples, inline examples and the converter.
- [x] Use generated `k/++`, `k/**`, `k/||`, `k/...`, `k/<<|` and existing
      operator Vars instead of string-based `az/op` in Learn. JVM array/string
      operations use the shared native bridge; native handles retain their types.
- [x] Document both `k/*` signatures: multiplication and loop-only pointer capture.
      Verified 2026-09-26: 58 focused tests / 1,014 assertions passed, plus 20
      native tests across 11 Learn files. Fixed the converter's unresolved error
      names encountered during verification.
- [x] Regenerate full HTML and invalidated REPL outputs after the array/operator
      cleanup; verify the original Zig document is unchanged and inspect the
      served page, including its code tabs and REPL output.
      Completed 2026-09-26: all 292 outcomes passed (288 upstream comparisons,
      4 reviewed special cases), 202 REPL transcripts, full acceptance passed.
      Output verification took 94.7 seconds; HTML assembly took 3.5 seconds.
      Original-document/output tests: 3 tests / 21 assertions passed; highlighting:
      6 tests passed. Reloaded localhost:63979 and interacted with the Arrays tabs;
      confirmed `az/array`, `k/++`, `k/**`, visible real test output, and no old
      `az/array-init` in any displayed Clojure example.

- [x] Make native `for` bindings flat, support `(k/* name)` pointer captures
      and `(az/range start [end])`, migrate examples/converter, and verify native
      declarations, direct JVM execution, zipped iteration, and editor hooks.
      Completed 2026-09-26: 57 focused tests / 328 assertions passed plus all
      12 native tests across arrays, multidimensional arrays, tuples, for,
      and nested break/continue in a clean owned REPL. Eight Learn examples
      migrated. Nested bindings rejected; multiplication remains binary/n-ary
      outside capture positions. Restart REPLs containing old declarations
      before loading the migrated syntax.

- [x] Fix `az/describe` for private declarations by reflecting in their owner
      module, without changing visibility. Regress both Var/function-value
      inspection and array versus string-literal-pointer JVM representations.
      Verified 2026-09-26: 6 tests / 53 assertions passed, and loaded the actual
      test_arrays.clj in the owned REPL: make-point describes successfully;
      explicit dereference/slice of same-message returns the bytes/"hello".
      Pointer printing remains non-dereferencing; the pointer/array distinction
      is genuine Zig typing, not a decoding failure.

- [x] Add `az/describe` to discover fields and callable members for native
      values/types, including concrete generic signatures and available
      documentation/accessor Vars. Verify arrays, slices, own containers,
      imported types and inspection without accessing native storage.
      Completed 2026-09-26: native reflection with public function signatures,
      field/accessor discovery, docs, and explicit constant/variable/nested-type
      groups. Feature tests: 4 tests / 39 assertions; nine related JVM bridge
      regression tests / 106 assertions also passed. No receiver memory reads
      or discovered-function execution during inspection.

- [x] Evaluate `az/clj!` at declaration execution, not macroexpansion, so
      enclosing Clojure let/fn bindings (including local helper functions)
      work naturally. Preserve once-only evaluation, literal validation,
      diagnostics and native hot reload; no eval-based recovery of locals.
      Completed 2026-09-26: declaration templates emit normal lexical Clojure
      expressions, then prepare/validate captured values before registration.
      Exact local sss example compared in native Zig; factory invocation and
      macroexpansion timing tested. 69 tests / 464 assertions passed across
      host-eval, emitter, API and editor-hook suites.

- [x] Add value-only `(az/clj! expression)` host evaluation in declaration
      type/value positions and nested expressions. Verify the three equivalent
      alt-message definitions, caller namespace/aliases, helper redefinition,
      once-only evaluation, cache invalidation, diagnostics, and native calls.
      No options or code-generation mode in this first version. Implemented
      2026-09-26, including once-only struct-field evaluation and normal Clojure
      try/catch analysis in editor hooks. Verified native equality of all three
      alt-message forms, computed signatures, helper redefinition and captured
      values. Host-eval/emitter/API/editor-hook suites: 66 tests, 453 assertions,
      zero failures or errors. Usage documented in README.md.

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
- [x] Preserve named vector types in examples: `test_reduce_builtin.clj`
      initializes through `V`; constructor shorthand must not bypass that alias.
- [ ] Generate source-spanned type reports for file forms and subforms, and
      expose them as Learn hover tooltips. Use only compiler/ZLS observations;
      report unavailable analysis explicitly, never guess from Clojure syntax.
- [x] Reuse guarded Panama handlers generically across applicable `k/...` and
      `az/...` calls, keyed by operation/native types instead of runtime values.
      Preserve comptime specialization, native ownership, constness and safety.
      Verify changed-value calls launch no compiler processes, rerun real lesson
      bodies, measure cold/warm calls, and regenerate the entire Learn HTML.
      Shared runtime plans and borrowed native views implemented; 18 bridge
      regressions / 190 assertions passed. Coercion/Learn-body regressions:
      eight tests / 139 assertions passed. Final reloaded-code checks: five
      tests / 41 assertions passed. Learn acceptance: 288 matched outcomes +
      four reviewed special cases, 202 regenerated REPL transcripts.
      Served HTML SHA-256 matches the rebuilt file. New signatures and genuinely
      comptime/source expressions still compile; see `NATIVE_HANDLERS_PLAN.md`.
- [x] Add explicit AOT-like JVM precompilation, separate from :prepare; verify
      concrete function bodies are not executed and handlers reuse disk artifacts
      across JVM restarts. Never execute workloads for discovery; dynamic call
      signatures must be supplied explicitly and analyzed by Zig.
      Added az/precompile! and :precompile with :namespaces, :calls and :coercions.
      Unsupported/specialization-dependent paths are reported, not executed.
      Five regression tests / 25 assertions passed, including a separate JVM
      making first calls with zero compiler builds after compile-only preparation.
      Existing bridge regressions also passed: 10 tests / 141 assertions.
      Normalized false/nil test-context cache identity; :prepare remains unchanged.
- [ ] Address macOS native-library TLS-key exhaustion during long-running,
      high-specialization stress runs. The reused regression JVM aborted with
      dyld's `could not create thread local variables pthread key`; it was not
      a completed green broad-suite run.
- [x] Reuse runtime array/slice handlers across array lengths. Canonicalize
      structural result types from Zig reflection, retain exact array types and
      pointer qualifiers, and verify the 10/11/12-element JVM bodies with compiler
      call counts, bounds checks, constness, and fixed-length slice regressions.
      New lengths now require only the array constructor build (~0.4 s locally),
      not eight builds (~10 s). Full values-lesson changed-value evaluation
      performs zero compiler calls after warm-up (~63 ms). Clean-session bridge
      checks passed: 14 tests / 146 assertions, including zero-length arrays,
      temporary pointer ownership, sentinel arrays, and typed overflow behavior.
      Final Learn regeneration passed: 288 matched outcomes, four reviewed
      special cases, 202 REPL transcripts; served HTML matches the rebuilt file.
