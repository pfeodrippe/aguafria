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

(deftest compile-and-invoke-test
  (testing "latest async module generation is callable"
    (is (= 12 (composed 3)))
    (is (= 45 (sum-to 10)))
    (is (= 20 (external-quadruple 5)))
    (is (= 10 (simd-sum4 1 2 3 4)))
    (is (= 42 (keyword-int-cast 42)))
    (is (= 6 (reader-safe-bit-xor 5 3)))
    (is (= 9 (abs-i32 -9)))
    (is (= 9 (abs-i32 9))))

  (testing "declarations are ordinary standalone Zig"
    (let [source (az/source 'aguafria.zig-integration-test)]
      (is (str/includes? source "const std = @import(\"std\");"))
      (is (str/includes? source "const extra_math = @import(\"extra_math\");"))
      (is (str/includes? source "pub const Point = extern struct"))
      (is (str/includes? source "pub const multiplier: i32 = 3;"))
      (is (str/includes? source "pub fn sum_point(point: Point) i32"))
      (is (not (str/includes? source "@import(\"aguafria")))))

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
  (testing "recompiling the module updates an already-defined Zig caller"
    (try
      (is (= 12 (composed 3)))
      (binding [*ns* (the-ns 'aguafria.zig-integration-test)]
        (eval
         '(az/defn base :- :i32
            [x :- :i32]
            (+ x 5))))
      (is (= 24 (composed 3)))
      (finally
        (binding [*ns* (the-ns 'aguafria.zig-integration-test)]
          (eval
           '(az/defn base
              "Increment an integer in Zig."
              :- :i32
              [x :- :i32]
              (+ x 1))))
        (az/await! 'aguafria.zig-integration-test)))))

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
      (is (zero? (:exit execution))))
    (testing "monitor-friendly statistics are plain inspectable data"
      (is (= :finished (:status module-stats)))
      (is (pos? (:declaration-count module-stats)))
      (is (some #(and (= "main" (:name %)) (= :finished (:state %)))
                (:declarations module-stats)))
      (is (some #(= :standalone-program (:purpose %)) (:builds module-stats)))
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
