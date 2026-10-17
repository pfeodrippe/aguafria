(ns learn.editor-check-failures
  "Evidence-based repair groups. A blocked compiler command is a symptom, not a diagnosis."
  (:require [clojure.string :as str]))

(def repairs
  {:undefined-token
   ["Resolve referred undefined by Var identity"
    "The producer rejects bare undefined although native-first namespaces refer the native Var. declaration-local-bindings incorrectly treats the first operand of k/var as a declared local; (k/var undefined :i32) therefore shadows the token during qualification. Stop treating mutable initializers as declarations of their operands, while retaining real lexical bindings. Do not qualify every consumer as a workaround."
    "The affected producer cases must get past this diagnostic; rerun their exact editor checks to expose any later blockers."]
   :test-object
   ["Publish the complete test-definition object, not only ordinary body adapters"
    "The namespace was admitted, and the requested test is the final native declaration. Collect this native-test-library! demand during compile-only preparation and publish its test object/index. Check the exact test filter and configuration. No prefix reconstruction is needed for the recorded source position."
    "Loading the same source must find the prepared test-definition artifact, with no test-obj command and without executing the test body."]
   :test-prefix
   ["Capture and publish intermediate test-definition snapshots"
    "The namespace was admitted, but declarations follow the requested test. Freeze the declaration prefix at that a/deftest, including its available dependencies and configuration. Prepare and index that prefix without running tests. Preparing only the final namespace or final native test is not evidence that this earlier snapshot exists."
    "Each load-time test filter must resolve its corresponding prefix artifact. Later declarations must neither leak into earlier validation nor silently change its cache key."]
   :transitive-test
   ["Prepare the required namespace's test definition before dependent loading"
    "The recorded test filter belongs to a required namespace, not the displayed lesson. Collect and publish that dependency's test-definition artifact once, using its actual declaration snapshot. The dependent lessons fail at the same require; they do not each need a distinct body adapter for this stop."
    "Strictly loading each dependent namespace reuses the dependency artifact. Check its own lesson too, then evaluate the previously hidden dependent forms."]
   :test-shape-unknown
   ["Unresolved test-object miss: identify the requested source snapshot"
    "The consumer requests a native test object, but the recorded filter could not be matched to a source declaration. Compare the request with the producer test artifact keys before choosing collection, publication, or key normalization as the fix."
    "Record the matched declaration and recipe difference, then verify strict loading. No root cause is claimed yet."]
   :linkage-context
   ["Prepare exported/object symbols in compatible linkage contexts"
    "The producer explicitly rejects a handler from the single AOT compilation. Inspect its export/entry/object configuration and use the existing dedicated linkage-context mechanism where applicable. Preserve authored ABI symbol names; do not force incompatible exports into the shared pack."
    "The producer must admit the required context and the consumer must resolve it without compiling a replacement image."]
   :float-object-link
   ["Link the strict and optimized floating-point companion symbols"
    "Admission reports undefined _foo_strict/_foo_optimized symbols. Supply the companion object implementations and their configuration in the preparation linkage graph; preparing the importing wrapper alone cannot satisfy them."
    "Admission links successfully and float-mode-exe loads without compilation."]
   :assembly-link
   ["Retain the global assembly symbol in the linked image"
    "Admission cannot resolve _my_func. Check assembly symbol spelling/export and target ABI decoration, then retain the assembly/object dependency in the prepared image."
    "The actual target linker resolves the symbol; a cached image is not a substitute for a successful link."]
   :host-print-import
   ["Supply the external print implementation or the intended target host"
    "Admission cannot resolve _print. Prepare the companion host/import linkage expected by the example, or validate it in its intended target environment. Do not manufacture a JVM callback silently."
    "Validate the intended host contract and its prepared artifact separately from native editor evaluation when the environments differ."]
   :editor-tls-reentry
   ["Resolve stateful helper re-entry in the exact editor workload"
    "The checkpoint shows testTls's assertion at 1234, increment, and assertion at 1235 all completed before thread-local-storage calls testTls again on that same editor thread. The second invocation expects 1234 and panics. This is not evidence of broken TLS isolation. Resetting x, skipping the helper body, or replacing the test call would change the specified workload; an explicit editor workflow decision is required."
    "Preserve the exact ordered receipts and do not report this stateful replay as a successful lesson or silently reset native storage."]
   :editor-test-context
   ["Distinguish ordinary editor execution from Zig test context"
    "The preceding editor result records builtin.is_test as false. The later testing/expect(isATest()) therefore fails in ordinary execution. A prepared native test is a different workload; do not substitute it or force the assertion true. Providing test context must be an explicit, shared editor/runtime contract, not checker-only behavior."
    "Keep ordinary and test-context observations distinct. The existing exact ordinary workload remains failed unless its context contract is explicitly changed."]
   :testing-context
   ["Preserve std.testing allocator context in ordinary prepared adapters"
    "The producer says not testing: generated ordinary handlers reach std.testing.allocator with builtin.is_test false. Carry the supported test-resource context through discovery and handler construction. If ordinary evaluation cannot provide that context, report it explicitly rather than replacing the editor body with a native test call."
    "The actual planned form uses a correctly owned test resource, without a not-testing diagnostic or hidden native-test substitution."]
   :assembly-target
   ["Validate x86 clobbers in an x86 assembly context"
    "The producer rejects r11 on this aarch64 host. Check the fixture's syntax-only/target intent and compile its assembly with the intended target. This is not fixed by preparing another aarch64 test object."
    "Target-specific syntax is validated appropriately; host editor execution is not claimed unless genuinely supported."]
   :calling-convention
   ["Use the intended backend for the unsupported calling convention"
    "The producer reports aarch64_aapcs_win unsupported by stage2_llvm. Resolve the example's target/backend contract, rather than treating the blocked consumer compile as a missing adapter alone."
    "The declared target/backend supports the convention, or the example is explicitly reported as non-host coverage."]
   :build-api
   ["Update the Build.Step.Compile.run use for the pinned Zig API"
    "The producer says Build.Step.Compile has no run member. Check the Zig 0.17 build API and update the consumer to the correct build/run-step operation without changing the lesson's behavior."
    "The producer diagnostic disappears under the pinned toolchain, then the editor scenario is checked again."]
   :wasi-api
   ["Repair the WASI preopens keys receiver/type context"
    "The producer attempts keys on void. Inspect the translated preopens call and its intended target API; establish the correct returned container before accessing keys. A namespace image alone cannot repair this type error."
    "Validate on the intended WASI context and keep host-only limitations explicit."]
   :signed-division
   ["Lower signed division with an explicit Zig rounding operation"
    "The producer rejects signed i32 / i32. Determine the intended division semantics and lower to the appropriate divTrunc/divFloor/divCeil/divExact operation, rather than assuming / is valid for signed operands."
    "Compare native results, including negative operands, with the lesson's intended behavior; then verify preparation."]
   :sentinel-string
   ["Retain the sentinel when supplying a C-string pointer"
    "The producer expects [*:0]const u8 but sees *const [14]u8. Preserve the literal's sentinel-bearing type through construction/coercion; do not invent a sentinel for arbitrary unsentinelized memory."
    "The native pointer has actual sentinel storage and the exact editor construction uses its prepared handler."]
   :and
   ["Add syntax-capturing native and/or with short-circuit behavior"
    "Use the existing scoped-token expansion path; do not implement eager argument functions or substitute Clojure truthiness. Preserve referred/qualified Var identity and ordinary let."
    "Check all four short-circuit paths with observable native side effects, then the values form with zero compiler attempts."]
   :defer
   ["Define an owning cleanup scope for standalone defer"
    "The standalone defer form has no enclosing native lifetime. Decide the supported editor cleanup boundary and implement that boundary in the real evaluator/bridge. Do not change only the checker or silently wrap the entire lesson."
    "Cleanup order, exactly-once execution and failure unwinding match the documented editor contract."]
   :unreachable
   ["Lower unreachable in the captured error branch"
    "runtime-unwrap-error reaches a placeholder rather than the intended native panic. Keep the catch branch as native syntax through scoped lowering."
    "The negative example reaches the expected native failure rather than a syntax-placeholder exception."]
   :callable-map
   ["Lift a declared native callable nested inside the std-options map"
    "The callable already carries native reference metadata. Extend native-construction-argument? to recognize supported callable references and route the aggregate through call-inputs, which already lifts those references. Do not classify every IFn as native, invoke the callable, or serialize a JVM function object."
    "The typed initializer works; ordinary closures remain rejected; preparation and consumption use the same canonical reference."]
   :error-set-transport
   ["Preserve inferred error-set provenance in error-branch transport"
    "UnableToReturnNumber reaches a destination error set that cannot represent it. Trace the declared return type through capture and transport, retaining the error's name and native schema. Do not widen every set or suppress the failure."
    "testing-error-with-if selects the error branch and prints the expected name without compilation."]
   :arena-method
   ["Collect the ArenaAllocator method specialization requested by cli-allocation"
    "Compare the attempted method recipe and receiver type with the producer's ArenaAllocator method demands. Collect/publish the exact supported specialization and its dependencies."
    "The whole allocation form completes using prepared artifacts; ownership and cleanup behavior are unchanged."]
   :inf-call
   ["Prepare the math/inf specialization for f32"
    "Match the consumer's comptime type argument :f32 and result representation to the imported-call recipe. Fix collection/key parity for that exact specialization."
    "The standalone math/inf form resolves from the bundle without invoking a compiler."]
   :list-transport
   ["Prepare transport for the instantiated List i32 aggregate"
    "Include the concrete List :i32 construction/transport shape, including its borrowed buffer pointer, in preparation. Compare the consumer transport schema with the producer's instantiated type."
    "The exact a/init form works from the bundle without detaching the buffer owner."]
   :node-construction
   ["Prepare the Node constructor image demanded by ordinary construction"
    "Compare the Node initializer's demanded declaration closure and configuration with the frozen image. Determine whether the constructor demand is absent or keyed differently before changing publication."
    "The recorded Node construction resolves the intended image without compilation."]
   :error-return-query
   ["Prepare the error-return/result-context query in math-add"
    "The consumer requests a compile-time query while lowering the captured error path. Preserve the result/error context in the collected query, then validate the intended runtime-negative result separately."
    "No query compiler command is needed during evaluation; the expected error is still observable."]
   :runtime-safety-directive
   ["Give setRuntimeSafety its actual lexical compilation context"
    "A standalone setter cannot establish safety semantics for later independently compiled forms merely by executing a new adapter. Define and preserve its owning native context, or report standalone use unsupported."
    "The check cannot be made green by warming an expression adapter that fails to carry the directive's semantics."]})

(defn diagnostic-family [message]
  (cond
    (re-find #"backwards branches|branch quota" message) :branch-quota
    (re-find #"outside array|index out of bounds" message) :index-bounds
    (re-find #"division by zero|division.*zero|exact division|shift.*bits|shift.*overflow|integer overflow|overflow of integer" message) :arithmetic
    (re-find #"align|bit width|u3'" message) :alignment-layout
    (re-find #"null|unwrap" message) :null-unwrap
    (re-find #"error set|error\{|error code|represents no error|unexpected error" message) :error-set
    (re-find #"union field|enum.*tag|invalid enum" message) :tagged-value
    (re-find #"unreachable" message) :unreachable
    :else :type-or-language-rule))

(defn classify [r]
  (let [message (:message r)
        diagnostics (str/join "\n" (:producer-diagnostics r))
        negative? (#{:compile-failure :runtime-failure :test-failure} (:upstream-expectation r))]
    (cond
      ;; Actual producer rejection takes priority over the generic blocked command.
      (and (= :rejected (:producer-status r)) (seq diagnostics))
      (cond
        (str/includes? diagnostics "Bare `undefined`") :undefined-token
        (str/includes? diagnostics "not testing") :testing-context
        (re-find #"undefined symbol: _foo_(strict|optimized)" diagnostics) :float-object-link
        (str/includes? diagnostics "undefined symbol: _my_func") :assembly-link
        (str/includes? diagnostics "undefined symbol: _print") :host-print-import
        (str/includes? diagnostics "Handler cannot be included") :linkage-context
        (re-find #"no field named 'r11'" diagnostics) :assembly-target
        (str/includes? diagnostics "aarch64_aapcs_win") :calling-convention
        (str/includes? diagnostics "Build.Step.Compile") :build-api
        (str/includes? diagnostics "named 'keys' in 'void'") :wasi-api
        (str/includes? diagnostics "signed integers must use") :signed-division
        (and (not negative?) (str/includes? diagnostics "found '*const [14]u8'")) :sentinel-string
        negative? [:expected-diagnostic (diagnostic-family diagnostics)]
        :else [:unresolved-producer (first (:producer-diagnostics r))])

      (and (pos? (:compiler-attempts r)) (= "native-test-definition" (:artifact-kind r)))
      (cond
        (= :rejected (:producer-status r)) [:unresolved-producer (str (:namespace r))]
        (get-in r [:test-snapshot :transitive?]) :transitive-test
        (= :prefix (get-in r [:test-snapshot :kind])) :test-prefix
        (= :complete (get-in r [:test-snapshot :kind])) :test-object
        :else :test-shape-unknown)

      (pos? (:compiler-attempts r))
      (get {'learn.example.cli-allocation :arena-method
            'learn.example.float-special-values :inf-call
            'learn.example.generic-data-structure :list-transport
            'learn.example.anonymous-struct-name :node-construction
            'learn.example.math-add :error-return-query
            'learn.example.panic-handler :runtime-safety-directive}
           (:namespace r) [:unresolved-recipe (str (:namespace r))])

      (and (= 'learn.example.test-thread-local-variables (:namespace r))
           (str/includes? message "reached unreachable code")
           (every? (fn [expression]
                     (some #(and (= expression (:expression %)) (= :evaluated (:status %)))
                           (:editor-observations r)))
                   ["(debug/assert (== x 1234))" "(+= x 1)" "(debug/assert (== x 1235))"]))
      :editor-tls-reentry

      (and (= 'learn.example.testing-detect-test (:namespace r))
           (= "Native Zig error: TestUnexpectedResult" message)
           (some #(and (= "builtin/is_test" (:expression %))
                        (= "#aguafria.zig.value.ZigValue[false]" (:result %))
                        (= :evaluated (:status %))) (:editor-observations r)))
      :editor-test-context

      (str/includes? message "keyword/and`") :and
      (str/includes? message "keyword/defer`") :defer
      (str/includes? message "keyword/unreachable`") :unreachable
      (and (= 'learn.example.std-options (:namespace r)) (= "Cannot emit Zig expression" message)) :callable-map
      (str/includes? message "Destination Zig error set") :error-set-transport
      negative? [:expected-diagnostic (diagnostic-family message)]
      :else [:unresolved-consumer message])))

(defn description [group]
  (or (get repairs group)
      (case (first group)
        :expected-diagnostic
        [(str "Validate the intended negative diagnostic: " (name (second group)))
         "Keep the deliberately invalid example unchanged. Match the specific upstream diagnostic/failure at the recorded declaration and stage. A producer diagnostic is separate evidence from the consumer's blocked compilation; it does not make the strict editor check pass. Preserve safety checks."
         "Report expected-failure verification and prepared-execution status separately. No unrelated error or compiler-policy exception may satisfy the expected diagnostic."]
        :unresolved-producer
        [(str "Resolve this producer rejection: " (second group))
         "The preserved producer diagnostic identifies a rejection but not a verified code repair. Inspect the original rejected analysis/admission artifact cited for this namespace. Establish whether it is intended, target-specific or a regression before selecting a fix. Do not lump it into test-object publication."
         "Record a reproduced root cause and targeted repair; the source must then pass preparation or its explicit expected-diagnostic check."]
        :unresolved-recipe
        [(str "Unresolved prepared-image recipe mismatch: " (second group))
         "Compare the exact attempted command/recipe with this namespace's producer artifacts. The current evidence does not distinguish missing collection, key divergence or publication failure. Do not invent a shared fix from the artifact family alone."
         "Identify the recipe difference and verify this namespace cold; later forms remain untested until loading succeeds."]
        [(str "Investigate the distinct consumer failure: " (second group))
         "Use this namespace's actual exception and checkpoint to establish its cause. No common repair has been demonstrated."
         "Add a reproducer and verify the chosen fix through the same strict editor workload."])))
