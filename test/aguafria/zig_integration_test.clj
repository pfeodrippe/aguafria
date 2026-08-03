(ns aguafria.zig-integration-test
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; Requiring this namespace exercises the opt-in parallel compiler. Each macro
;; queues a complete immutable snapshot; the first invocation awaits the newest.
(az/configure! {:async? true
                :modules {"extra_math" "test/fixtures/extra_math.zig"}})

(az/defimport std "std" [])

(az/defimport extra-math "extra_math" [quadruple])

(az/defstruct Point
  [[:x {:doc "Horizontal coordinate"} :i32]
   [:y :i32]])

(az/defconst multiplier :i32 3)

(az/defn ^{:export false :public true} sum-point :- :i32
  [point :- Point]
  (+ (field point x) (field point y)))

(az/defn constructed-point-sum :- :i32
  []
  (sum-point (Point {:y 5 :x 4})))

(az/defn base
  "Increment an integer in Zig."
  :- :i32
  [x :- :i32]
  (+ x 1))

(az/defn external-quadruple :- :i32
  [x :- :i32]
  (extra-math/quadruple x))

(az/defn ^{:export false :public true} simd-lane-sum :- :i32
  [values :- (ak/Vector 4 :i32)]
  (ak/reduce :.Add values))

(az/defn keyword-int-cast :- :i32
  [value :- :i64]
  (ak/intCast value))

(az/defn reader-safe-bit-xor :- :u32
  [left :- :u32 right :- :u32]
  (ak/bit-xor left right))

(az/defn simd-sum4 :- :i32
  [a :- :i32 b :- :i32 c :- :i32 d :- :i32]
  (simd-lane-sum [a b c d]))

(az/defn composed :- :i32
  [x :- :i32]
  (* (base x) multiplier))

(az/defn ^{:export false :public true} zig-only-base :- :i32
  [x :- :i32]
  (+ x 4))

(az/defn zig-only-composed :- :i32
  [x :- :i32]
  (* (zig-only-base x) 2))

(az/defn ^{:export false :public true} comptime-plus-one :- :u32
  [x :- :u32]
  (+ x 1))

(az/defconst comptime-answer :u32 (comptime-plus-one 41))

(az/defn comptime-answer-value :- :u32
  []
  comptime-answer)

(az/defn sum-to :- :i32
  [n :- :i32]
  (var total :i32 0)
  (var i :i32 0)
  (while (< i n)
    (+= total i)
    (+= i 1))
  total)

(az/defn abs-i32 :- :i32
  [x :- :i32]
  (if (< x 0)
    (- x)
    x))

(az/defn ^{:export false :public true} main :- :void
  [])

(defn- declaration-stat
  [name]
  (some #(when (= name (:name %)) %)
        (:declarations (az/stats 'aguafria.zig-integration-test))))

(deftest compile-and-invoke-test
  (testing "latest async module generation is callable"
    (is (= 12 (composed 3)))
    (is (= 9 (constructed-point-sum)))
    (is (= 45 (sum-to 10)))
    (is (= 20 (external-quadruple 5)))
    (is (= 10 (simd-sum4 1 2 3 4)))
    (is (= 42 (keyword-int-cast 42)))
    (is (= 6 (reader-safe-bit-xor 5 3)))
    (is (= 42 (comptime-answer-value)))
    (is (= 9 (abs-i32 -9)))
    (is (= 9 (abs-i32 9))))

  (testing "declarations are ordinary standalone Zig"
    (let [source (az/source 'aguafria.zig-integration-test)
          reload-source (:reload-source
                         (az/module-info 'aguafria.zig-integration-test))]
      (is (str/includes? source "const std = @import(\"std\");"))
      (is (str/includes? source "const extra_math = @import(\"extra_math\");"))
      (is (str/includes? source "pub const Point = extern struct"))
      (is (str/includes? source "pub const multiplier: i32 = 3;"))
      (is (str/includes? source "pub fn sum_point(point: Point) i32"))
      (is (str/includes? source "Point{.x = 4, .y = 5}"))
      (is (not (str/includes? source "@import(\"aguafria")))
      (is (not (str/includes? source "__aguafria_")))
      (is (str/includes? reload-source "_set_dispatch"))))

  (testing "external Zig members are real, inspectable Clojure Vars"
    (let [test-ns (the-ns 'aguafria.zig-integration-test)
          member (ns-resolve test-ns 'extra-math/quadruple)]
      (is (var? (ns-resolve test-ns 'std)))
      (is (var? member))
      (is (= "extra_math.quadruple"
             (get-in (meta member) [:aguafria/zig-reference :zig-name])))))

  (testing "newest generation has been published"
    (let [{:keys [generation requested-generation pending? error source-path
                  library-path]} (az/module-info 'aguafria.zig-integration-test)]
      (is (= generation requested-generation))
      (is (false? pending?))
      (is (nil? error))
      (is (.isFile (java.io.File. source-path)))
      (is (.isFile (java.io.File. library-path))))))

(deftest repl-metadata-test
  (is (= "Increment an integer in Zig." (:doc (meta #'base))))
  (is (= '([x :- :i32]) (:arglists (meta #'base))))
  (is (= :fn (get-in (meta #'base) [:aguafria/declaration :kind])))
  (is (= ["aguafria.zig-integration-test" :fn "base"]
         (get-in (meta #'base) [:aguafria/declaration :logical-id])))
  (is (= 64 (count (get-in (meta #'base)
                           [:aguafria/declaration :abi-fingerprint]))))
  (is (= 64 (count (get-in (meta #'Point)
                           [:aguafria/declaration :schema-fingerprint]))))
  (is (= '[(aguafria.keyword/intCast value)]
         (get-in (meta #'keyword-int-cast) [:aguafria/declaration :body])))
  (is (= '(aguafria.keyword/Vector 4 :i32)
         (get-in (meta #'simd-lane-sum)
                 [:aguafria/declaration :args 0 :type])))
  (is (= {:doc "Horizontal coordinate"}
         (get-in (meta #'Point)
                 [:aguafria/declaration :fields 0 :properties]))))

(deftest explicit-recompile-test
  (az/await! 'aguafria.zig-integration-test)
  (let [before (:published-generation
                (az/stats 'aguafria.zig-integration-test))]
    (az/recompile! 'aguafria.zig-integration-test)
    (az/await! 'aguafria.zig-integration-test)
    (is (= (inc before)
           (:published-generation
            (az/stats 'aguafria.zig-integration-test))))
    (is (= 10 (simd-sum4 1 2 3 4)))))

(deftest hot-reload-test
  (testing "a compatible callee edit repoints an already-compiled Zig caller"
    (try
      (is (= 12 (composed 3)))
      (let [base-before (declaration-stat "base")
            composed-before (declaration-stat "composed")]
        (binding [*ns* (the-ns 'aguafria.zig-integration-test)]
          (eval
           '(az/defn base :- :i32
              [x :- :i32]
              (+ x 5))))
        (is (= 24 (composed 3)))
        (let [base-after (declaration-stat "base")
              composed-after (declaration-stat "composed")
              stats-after (az/stats 'aguafria.zig-integration-test)
              retained-generations
              (into #{} (map :generation) (:native-generations stats-after))]
          (is (< (:implementation-generation base-before)
                 (:implementation-generation base-after)))
          (is (= (:implementation-generation composed-before)
                 (:implementation-generation composed-after)))
          (is (contains? retained-generations
                         (:implementation-generation base-after)))
          (is (contains? retained-generations
                         (:implementation-generation composed-after)))
          (is (pos? (:dispatch-version-count stats-after)))))
      (finally
        (binding [*ns* (the-ns 'aguafria.zig-integration-test)]
          (eval
           '(az/defn base
              "Increment an integer in Zig."
              :- :i32
              [x :- :i32]
              (+ x 1))))
        (az/await! 'aguafria.zig-integration-test)))))

(deftest zig-only-compatible-callee-hot-reload-test
  (testing "a non-C-exported Zig helper repoints its already-compiled Zig caller"
    (try
      (is (= 14 (zig-only-composed 3)))
      (let [callee-before (declaration-stat "zig-only-base")
            caller-before (declaration-stat "zig-only-composed")]
        (binding [*ns* (the-ns 'aguafria.zig-integration-test)]
          (eval
           '(az/defn ^{:export false :public true} zig-only-base :- :i32
              [x :- :i32]
              (+ x 10))))
        (is (= 26 (zig-only-composed 3)))
        (let [callee-after (declaration-stat "zig-only-base")
              caller-after (declaration-stat "zig-only-composed")]
          (is (< (:implementation-generation callee-before)
                 (:implementation-generation callee-after)))
          (is (= (:implementation-generation caller-before)
                 (:implementation-generation caller-after)))))
      (finally
        (binding [*ns* (the-ns 'aguafria.zig-integration-test)]
          (eval
           '(az/defn ^{:export false :public true} zig-only-base :- :i32
              [x :- :i32]
              (+ x 4))))
        (az/await! 'aguafria.zig-integration-test)))))

(deftest add-function-and-rewire-existing-caller-test
  (let [pid (.pid (java.lang.ProcessHandle/current))]
    (binding [*ns* (the-ns 'aguafria.zig-integration-test)]
      (eval
       '(az/defn live-existing-b :- :i32
          [x :- :i32]
          (+ x 2))))
    (is (= 5 ((ns-resolve (the-ns 'aguafria.zig-integration-test)
                          'live-existing-b)
              3)))
    (binding [*ns* (the-ns 'aguafria.zig-integration-test)]
      (eval
       '(az/defn live-new-a :- :i32
          [x :- :i32]
          (* x 10)))
      (eval
       '(az/defn live-existing-b :- :i32
          [x :- :i32]
          (+ (live-new-a x) 2))))
    (is (= 32 ((ns-resolve (the-ns 'aguafria.zig-integration-test)
                           'live-existing-b)
               3)))
    (is (= pid (.pid (java.lang.ProcessHandle/current))))))

(deftest add-cross-namespace-function-and-hot-rewire-caller-test
  (testing "a new A Var can be used by new and existing B Vars without restart"
    (let [old-config (az/configuration)
          suffix (str (random-uuid))
          a-symbol (symbol (str "aguafria.live-a-" suffix))
          b-symbol (symbol (str "aguafria.live-b-" suffix))
          a-ns (create-ns a-symbol)
          b-ns (create-ns b-symbol)
          pid (.pid (java.lang.ProcessHandle/current))]
      (try
        (az/configure! {:async? true})
        (doseq [target [a-ns b-ns]]
          (binding [*ns* target]
            (refer 'clojure.core)
            (alias 'az 'aguafria.zig)))
        (binding [*ns* b-ns]
          (alias 'a a-symbol)
          (eval
           '(az/defn existing-b :- :i32
              [x :- :i32]
              (+ x 2))))
        (is (= 5 ((ns-resolve b-ns 'existing-b) 3)))

        (binding [*ns* a-ns]
          (eval
           '(az/defn new-a :- :i32
              [x :- :i32]
              (* x 10)))
          (eval
           '(az/defn ^{:export false :public true} zig-only-a :- :i32
              [x :- :i32]
              (* x 3))))
        (is (= 30 ((ns-resolve a-ns 'new-a) 3)))

        (binding [*ns* b-ns]
          (eval
           '(az/defn existing-b :- :i32
              [x :- :i32]
              (+ (a/new-a x) 2)))
          (eval
           '(az/defn new-b :- :i32
              [x :- :i32]
              (+ (a/new-a x) 5)))
          (eval
           '(az/defn zig-only-b :- :i32
              [x :- :i32]
              (a/zig-only-a x))))
        (is (= 32 ((ns-resolve b-ns 'existing-b) 3)))
        (is (= 35 ((ns-resolve b-ns 'new-b) 3)))
        (is (= 9 ((ns-resolve b-ns 'zig-only-b) 3)))
        (let [b-generation-before
              (->> (:declarations (az/stats b-symbol))
                   (some #(when (= "existing-b" (:name %))
                            (:implementation-generation %))))
              a-v1-abi (-> (ns-resolve a-ns 'new-a)
                           meta :aguafria/declaration :abi-fingerprint)]
          (binding [*ns* a-ns]
            (eval
             '(az/defn new-a :- :i32
                [x :- :i32]
                (* x 20)))
            (eval
             '(az/defn ^{:export false :public true} zig-only-a :- :i32
                [x :- :i32]
                (* x 4))))
          (az/await! a-symbol)
          (is (= 62 ((ns-resolve b-ns 'existing-b) 3)))
          (is (= 65 ((ns-resolve b-ns 'new-b) 3)))
          (is (= 12 ((ns-resolve b-ns 'zig-only-b) 3)))
          (let [b-generation-after
                (->> (:declarations (az/stats b-symbol))
                     (some #(when (= "existing-b" (:name %))
                              (:implementation-generation %))))]
            (is (= b-generation-before b-generation-after)))

          (binding [*ns* a-ns]
            (eval
             '(az/defn new-a :- :i32
                [x :- :i32 y :- :i32]
                (+ (* x 20) y))))
          (az/await! a-symbol)
          (is (= 67 ((ns-resolve a-ns 'new-a) 3 7)))
          (is (= 62 ((ns-resolve b-ns 'existing-b) 3)))
          (is (= 65 ((ns-resolve b-ns 'new-b) 3)))
          (is (= 60 (az/invoke-version! (symbol (str a-symbol) "new-a")
                                        a-v1-abi [3])))
          (is (= 2 (count (az/function-versions
                           (ns-resolve a-ns 'new-a)))))

          ;; Both stale callers are reevaluated before the async quiet point;
          ;; their newest complete B snapshot binds to A@v2 atomically.
          (binding [*ns* b-ns]
            (eval
             '(az/defn existing-b :- :i32
                [x :- :i32]
                (+ (a/new-a x 7) 2)))
            (eval
             '(az/defn new-b :- :i32
                [x :- :i32]
                (+ (a/new-a x 7) 5))))
          (az/await! b-symbol)
          (is (= 69 ((ns-resolve b-ns 'existing-b) 3)))
          (is (= 72 ((ns-resolve b-ns 'new-b) 3)))
          (let [artifact (az/build! b-symbol
                                    {:kind :dynamic-lib
                                     :name (str "cross-namespace-" suffix)
                                     :optimize "ReleaseFast"})
                dependency-prefix (str "-M" a-symbol "=")
                dependency-argument
                (some #(when (str/starts-with? % dependency-prefix) %)
                      (:command artifact))
                dependency-source
                (subs dependency-argument (count dependency-prefix))]
            (is (.isFile (java.io.File. (:output-path artifact))))
            (is (not (str/includes? (slurp (:source-path artifact))
                                    "__aguafria_")))
            (is (not (str/includes? (slurp dependency-source)
                                    "__aguafria_")))))
        (is (= pid (.pid (java.lang.ProcessHandle/current))))
        (is (str/includes? (az/source b-symbol)
                           (str "@import(\"" a-symbol "\")")))
        (let [all-stats (az/stats)
              a-stats (get-in all-stats [:modules (str a-symbol)])
              b-stats (get-in all-stats [:modules (str b-symbol)])]
          (is (= [(str a-symbol)] (:dependencies b-stats)))
          (is (some #{(str b-symbol)} (:dependents a-stats)))
          (is (= [(str b-symbol)] (:dependency-component-modules b-stats)))
          (is (false? (:cyclic-dependency-component? b-stats))))
        (finally
          (az/configure! old-config)
          (remove-ns b-symbol)
          (remove-ns a-symbol))))))

(deftest handwritten-cyclic-module-hot-reload-test
  (testing "ordinary Aguafria namespaces can form and hot-reload a Zig cycle"
    (let [old-config (az/configuration)
          suffix (str (random-uuid))
          a-symbol (symbol (str "aguafria.cyclic-a-" suffix))
          b-symbol (symbol (str "aguafria.cyclic-b-" suffix))
          a-ns (create-ns a-symbol)
          b-ns (create-ns b-symbol)]
      (try
        (az/configure! {:async? false})
        (doseq [target [a-ns b-ns]]
          (binding [*ns* target]
            (refer 'clojure.core)
            (alias 'az 'aguafria.zig)))
        (binding [*ns* a-ns]
          (alias 'b b-symbol)
          (eval
           '(az/defn ^{:export false :public true} inner-a :- :i32
              [x :- :i32]
              (+ x 1))))
        (binding [*ns* b-ns]
          (alias 'a a-symbol)
          (eval
           '(az/defn ^{:export false :public true} call-a :- :i32
              [x :- :i32]
              (+ (a/inner-a x) 2))))
        (binding [*ns* a-ns]
          (eval
           '(az/defn entry :- :i32
              [x :- :i32]
              (b/call-a x))))
        (is (= 8 ((ns-resolve a-ns 'entry) 5)))
        (let [caller-generation-before
              (->> (:declarations (az/stats a-symbol))
                   (some #(when (= "entry" (:name %))
                            (:implementation-generation %))))]
          (binding [*ns* a-ns]
            (eval
             '(az/defn ^{:export false :public true} inner-a :- :i32
                [x :- :i32]
                (+ x 10))))
          (is (= 17 ((ns-resolve a-ns 'entry) 5)))
          (let [caller-generation-after
                (->> (:declarations (az/stats a-symbol))
                     (some #(when (= "entry" (:name %))
                              (:implementation-generation %))))
                a-stats (az/stats a-symbol)
                b-stats (az/stats b-symbol)]
            (is (= caller-generation-before caller-generation-after))
            (is (:cyclic-dependency-component? a-stats))
            (is (:cyclic-dependency-component? b-stats))
            (is (= #{(str a-symbol) (str b-symbol)}
                   (set (:dependency-component-modules a-stats))))))
        (finally
          (az/configure! old-config)
          (remove-ns b-symbol)
          (remove-ns a-symbol))))))

(deftest cross-namespace-active-call-survives-callee-swap-test
  (testing "a caller-library invocation keeps the old callee library alive through publication"
    (let [old-config (az/configuration)
          suffix (str (random-uuid))
          a-symbol (symbol (str "aguafria.active-live-a-" suffix))
          b-symbol (symbol (str "aguafria.active-live-b-" suffix))
          a-ns (create-ns a-symbol)
          b-ns (create-ns b-symbol)
          worker (atom nil)]
      (try
        (az/configure! {:async? false})
        (doseq [target [a-ns b-ns]]
          (binding [*ns* target]
            (refer 'clojure.core)
            (alias 'az 'aguafria.zig)
            (alias 'ak 'aguafria.keyword)))
        (binding [*ns* a-ns]
          (eval
           '(az/defn spin :- :u64
              [n :- :u64]
              (ak/var i :u64 0)
              (while (< i n)
                (+= i 1))
              i)))
        (binding [*ns* b-ns]
          (alias 'a a-symbol)
          (eval
           '(az/defn call-spin :- :u64
              [n :- :u64]
              (a/spin n))))
        (let [b-generation-before
              (->> (:declarations (az/stats b-symbol))
                   (some #(when (= "call-spin" (:name %))
                            (:implementation-generation %))))]
          (reset! worker
                  (future ((ns-resolve b-ns 'call-spin) 5000000000)))
          (is (loop [attempt 0]
                (cond
                  (some #(pos? (:native-active-call-count %))
                        (:native-generations (az/stats a-symbol))) true
                  (< attempt 500) (do (Thread/sleep 10)
                                      (recur (inc attempt)))
                  :else false))
              "the imported A implementation should report the native call")
          (binding [*ns* a-ns]
            (eval
             '(az/defn spin :- :u64
                [n :- :u64]
                (ak/var i :u64 0)
                (while (< i n)
                  (+= i 1))
                (+ i 1))))
          (let [during (az/stats a-symbol)
                b-generation-after
                (->> (:declarations (az/stats b-symbol))
                     (some #(when (= "call-spin" (:name %))
                              (:implementation-generation %))))]
            (is (some :retirement-pending? (:native-generations during)))
            (is (= b-generation-before b-generation-after))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"Cannot clear Aguafria while native calls are active"
                                  (az/clear!))))
          (is (= 5000000000 (deref @worker 30000 ::timed-out)))
          (is (= 11 ((ns-resolve b-ns 'call-spin) 10)))
          (is (pos? (:retired-generation-count (az/stats a-symbol)))))
          (finally
            (when (and @worker (not (realized? @worker)))
              (deref @worker 30000 ::timed-out))
            (az/configure! old-config)
            (remove-ns b-symbol)
            (remove-ns a-symbol))))))

(deftest quiescent-native-generation-retirement-test
  (testing "an obsolete shared library is unloaded after its calls and implementations quiesce"
    (let [old-config (az/configuration)
          module (str "aguafria.retirement-fixture-" (random-uuid))
          qualified-name (symbol module "probe")
          declaration
          (fn [body]
            {:kind :fn
             :name 'probe
             :qualified-name qualified-name
             :declaration-key [:fn 'probe]
             :module module
             :args [{:name 'x :type :i32 :properties {}}]
             :return :i32
             :body body
             :export? true
             :public? true
             :implicit-return? true
             :source {:file "test/aguafria/zig_integration_test.clj"
                      :line (:line (meta #'quiescent-native-generation-retirement-test))
                      :column 1}})]
      (try
        (az/configure! {:async? false})
        (runtime/register-declaration! (declaration ['x]))
        (is (= 7 (runtime/invoke! qualified-name [7])))
        (is (= 1 (:native-generation-count (az/stats module))))

        (runtime/register-declaration!
         (declaration [(list '+ 'x 1)]))
        (is (= 8 (runtime/invoke! qualified-name [7])))
        (let [module-stats (az/stats module)
              retired (last (:retired-generations module-stats))]
          (is (= 1 (:native-generation-count module-stats)))
          (is (= 1 (:retired-generation-count module-stats)))
          (is (zero? (:native-active-call-count retired)))
          (is (zero? (:jvm-active-call-count retired))))
        (finally
          (az/configure! old-config))))))

(deftest active-native-call-survives-publication-test
  (testing "publishing a replacement retains an old library until its in-flight call returns"
    (let [old-config (az/configuration)
          module (str "aguafria.active-call-fixture-" (random-uuid))
          qualified-name (symbol module "spin")
          worker (atom nil)
          declaration
          (fn [offset]
            {:kind :fn
             :name 'spin
             :qualified-name qualified-name
             :declaration-key [:fn 'spin]
             :module module
             :args [{:name 'n :type :u64 :properties {}}]
             :return :u64
             :body [(list 'var 'i :u64 0)
                    (list 'while (list '< 'i 'n)
                          (list '+= 'i 1))
                    (if (zero? offset) 'i (list '+ 'i offset))]
             :export? true
             :public? true
             :implicit-return? true
             :source {:file "test/aguafria/zig_integration_test.clj"
                      :line (:line (meta #'active-native-call-survives-publication-test))
                      :column 1}})
          active?
          (fn []
            (some #(pos? (:native-active-call-count %))
                  (:native-generations (az/stats module))))]
      (try
        (az/configure! {:async? false})
        (runtime/register-declaration! (declaration 0))
        (reset! worker (future (runtime/invoke! qualified-name [5000000000])))
        (is (loop [attempt 0]
              (cond
                (active?) true
                (< attempt 500) (do (Thread/sleep 10) (recur (inc attempt)))
                :else false))
            "the test call should enter native code")

        (runtime/register-declaration! (declaration 1))
        (let [during (az/stats module)]
          (is (= 2 (:native-generation-count during)))
          (is (some :retirement-pending? (:native-generations during))))

        (is (= 5000000000 (deref @worker 30000 ::timed-out)))
        (is (= 11 (runtime/invoke! qualified-name [10])))
        (let [after (az/stats module)]
          (is (= 1 (:native-generation-count after)))
          (is (= 1 (:retired-generation-count after))))
        (finally
          (when (and @worker (not (realized? @worker)))
            (deref @worker 30000 ::timed-out))
          (az/configure! old-config))))))

(deftest callable-abi-version-coexistence-test
  (testing "a breaking signature publishes v2 while v1 remains callable"
    (let [old-config (az/configuration)
          module (str "aguafria.abi-version-fixture-" (random-uuid))
          qualified-name (symbol module "calculate")
          declaration
          (fn [args body]
            {:kind :fn
             :name 'calculate
             :qualified-name qualified-name
             :declaration-key [:fn 'calculate]
             :module module
             :args args
             :return :i32
             :body body
             :export? true
             :public? true
             :implicit-return? true
             :source {:file "test/aguafria/zig_integration_test.clj"
                      :line (:line (meta #'callable-abi-version-coexistence-test))
                      :column 1}})
          v1 (runtime/declaration-info
              (declaration [{:name 'x :type :i32 :properties {}}]
                           [(list '+ 'x 1)]))
          v2 (runtime/declaration-info
              (declaration [{:name 'x :type :i32 :properties {}}
                            {:name 'y :type :i32 :properties {}}]
                           [(list '+ 'x 'y)]))]
      (try
        (az/configure! {:async? false})
        (runtime/register-declaration! v1)
        (is (= 8 (runtime/invoke! qualified-name [7])))

        (runtime/register-declaration! v2)
        (is (= 12 (runtime/invoke! qualified-name [5 7])))
        (is (= 8 (az/invoke-version! qualified-name
                                     (:abi-fingerprint v1) [7])))
        (let [versions (az/function-versions qualified-name)
              by-abi (into {} (map (juxt :abi-fingerprint identity)) versions)
              module-stats (az/stats module)]
          (is (= 2 (count versions)))
          (is (false? (get-in by-abi [(:abi-fingerprint v1) :current?])))
          (is (true? (get-in by-abi [(:abi-fingerprint v2) :current?])))
          (is (= 2 (:native-generation-count module-stats)))
          (is (= 2 (:dispatch-version-count module-stats))))
        (finally
          (az/configure! old-config))))))

(deftest breaking-callee-keeps-old-caller-live-test
  (testing "a breaking A publishes independently while old B keeps calling A v1"
    (let [old-config (az/configuration)
          module (str "aguafria.breaking-caller-fixture-" (random-uuid))
          a-name (symbol module "a")
          b-name (symbol module "b")
          declaration
          (fn [name args body]
            {:kind :fn
             :name name
             :qualified-name (symbol module (str name))
             :declaration-key [:fn name]
             :module module
             :args args
             :return :i32
             :body body
             :export? true
             :public? true
             :implicit-return? true
             :source {:file "test/aguafria/zig_integration_test.clj"
                      :line (:line (meta #'breaking-callee-keeps-old-caller-live-test))
                      :column 1}})
          arg-x {:name 'x :type :i32 :properties {}}
          arg-y {:name 'y :type :i32 :properties {}}
          a-v1 (runtime/declaration-info
                (declaration 'a [arg-x] [(list '+ 'x 1)]))
          a-v2 (runtime/declaration-info
                (declaration 'a [arg-x arg-y] [(list '+ 'x 'y)]))
          b-v1 (declaration 'b [arg-x]
                            [(list '+ (list 'a 'x) 100)])
          b-v2 (declaration 'b [arg-x]
                            [(list '+ (list 'a 'x 10) 100)])]
      (try
        (az/configure! {:async? true})
        (runtime/register-declaration! a-v1)
        (runtime/register-declaration! b-v1)
        (is (= 106 (runtime/invoke! b-name [5])))
        (let [b-generation-before
              (->> (:declarations (az/stats module))
                   (some #(when (= "b" (:name %))
                            (:implementation-generation %))))]
          (runtime/register-declaration! a-v2)
          (az/await! module)
          (let [partial-stats (az/stats module)
                b-generation-after
                (->> (:declarations partial-stats)
                     (some #(when (= "b" (:name %))
                              (:implementation-generation %))))]
            (is (:partial-publication? partial-stats))
            (is (string? (:full-compile-error partial-stats)))
            (is (= b-generation-before b-generation-after)))

          (is (= 12 (runtime/invoke! a-name [5 7])))
          (is (= 106 (runtime/invoke! b-name [5])))
          (is (= 6 (az/invoke-version! a-name
                                      (:abi-fingerprint a-v1) [5])))

          (runtime/register-declaration! b-v2)
          (is (= 115 (runtime/invoke! b-name [5])))
          (is (false? (:partial-publication? (az/stats module)))))
        (finally
          (az/configure! old-config))))))

(deftest standalone-build-and-stats-test
  (let [artifact (az/build! 'aguafria.zig-integration-test
                            {:kind :exe
                             :name "aguafria-integration"
                             :optimize "ReleaseFast"})
        execution (shell/sh (:output-path artifact))
        module-stats (az/stats 'aguafria.zig-integration-test)
        all-stats (az/stats)]
    (testing "the same generated source builds as an optimized pure Zig program"
      (is (= :exe (:kind artifact)))
      (is (= "ReleaseFast" (:optimize artifact)))
      (is (.isFile (java.io.File. (:output-path artifact))))
      (is (some #{"-OReleaseFast"} (:command artifact)))
      (is (not (str/includes? (slurp (:source-path artifact))
                              "__aguafria_")))
      (is (zero? (:exit execution))))
    (testing "monitor-friendly statistics are plain inspectable data"
      (is (= :finished (:status module-stats)))
      (is (pos? (:declaration-count module-stats)))
      (is (some #(and (= "base" (:name %))
                      (= 64 (count (:abi-fingerprint %))))
                (:declarations module-stats)))
      (is (some #(and (= "Point" (:name %))
                      (= 64 (count (:schema-fingerprint %))))
                (:declarations module-stats)))
      (is (some #(and (= "main" (:name %)) (= :finished (:state %)))
                (:declarations module-stats)))
      (is (some #(= :standalone-program (:purpose %)) (:builds module-stats)))
      (is (pos? (:native-generation-count module-stats)))
      (is (pos? (:dispatch-version-count module-stats)))
      (is (every? integer?
                  (keep :implementation-generation
                        (:dispatch-versions module-stats))))
      (is (zero? (get-in all-stats [:summary :active-build-count])))
      (is (pos? (get-in all-stats [:summary :finished-build-count]))))))

(deftest scalar-boundary-test
  (binding [*ns* (the-ns 'aguafria.zig-integration-test)]
    (eval
     '(az/defn truthy :- :bool
        [x :- :i32]
        (> x 0))))
  (let [test-ns (the-ns 'aguafria.zig-integration-test)]
    (is (true? ((ns-resolve test-ns 'truthy) 1)))
    (is (false? ((ns-resolve test-ns 'truthy) 0)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Wrong number of arguments"
                          ((ns-resolve test-ns 'truthy) 1 2)))))

(deftest rust-style-compiler-diagnostic-test
  (let [old-config (az/configuration)]
    (try
      (az/configure! {:async? false})
      (let [error (try
                    (runtime/register-declaration!
                     {:kind :const
                      :name 'broken-constant
                      :declaration-key [:const 'broken-constant]
                      :module "aguafria.error-fixture"
                      :type :i32
                      :value 'not-defined-in-zig
                      :public? true
                      :source {:file "test/aguafria/zig_integration_test.clj"
                               :line (:line (meta #'rust-style-compiler-diagnostic-test))
                               :column 1}})
                    nil
                    (catch clojure.lang.ExceptionInfo error error))]
        (is (some? error))
        (is (= :zig-compile (:aguafria/phase (ex-data error))))
        (is (seq (:diagnostics (ex-data error))))
        (is (str/includes? (ex-message error) "error[aguafria::zig]"))
        (is (str/includes? (ex-message error) "test/aguafria/zig_integration_test.clj"))
        (is (str/includes? (ex-message error) "this Clojure form generated the failing Zig"))
        (is (str/includes? (ex-message error) "Zig reported the error here"))
        (is (str/includes? (ex-message error) "compiler command")))
      (finally
        (az/configure! old-config)))))
