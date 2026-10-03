(ns aguafria.zig.jvm-pointer-context-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.source-map :as source-map]
            [aguafria.zig.value :as value]
            [clojure.test :refer [deftest is testing]]))

(def ^:private lessons
  ["test_integer_pointer_conversion" "test_comptime_pointer_conversion"
   "test_pointer_casting"])

(deftest actual-pointer-lesson-bodies-run-in-the-jvm
  (doseq [lesson lessons]
    (let [file (str "examples/learn/resources/learn/example/" lesson ".clj")
          forms (source-map/read-forms (slurp file))
          declaration (first (filter #(and (seq? %) (= 'a/deftest (first %))) forms))
          body (first (filter #(and (seq? %) (= 'let (first %)))
                              (tree-seq coll? seq (drop 2 declaration))))]
      (testing lesson
        (is (some? body) "Evaluate the actual let, not the native test entry point")
        (binding [*ns* *ns* *file* file]
          (doseq [form (take-while #(not= declaration %) forms)]
            (eval form))
          (is (= {:ok nil} (eval body)))
          (when (= 'k/comptime (first (nth declaration 2)))
            (is (= {:ok nil} (eval (nth declaration 2))))))
        (binding [*ns* (the-ns (second (first forms))) *file* file]
          ;; Separately verify native Zig compilation/execution of the same test.
          (eval declaration)
          (is (= :passed (:status ((ns-resolve *ns* (second declaration)))))))))))

(deftest jvm-comptime-is-identity-not-a-second-execution
  (let [calls (atom 0)
        marker (Object.)]
    (is (identical? marker (k/comptime (do (swap! calls inc) marker))))
    (is (= 1 @calls))
    (is (nil? (k/comptime nil)))
    (is (false? (k/comptime false)))))

(deftest contextual-casts-keep-zig-result-types-and-owners
  (let [pending (k/ptrFromInt 0xdeadbee0)]
    (is (instance? aguafria.zig.jvm.ContextualCall pending))
    (is (re-find #"result type required" (pr-str pending)))
    (with-open [pointer (k/as pending [:* :i32])]
      (is (= 0xdeadbee0 (a/value (k/intFromPtr pointer))))))
  (with-open [integer (k/as (k/intCast 1234) :u16)
              bits (k/as (k/bitCast (a/array [0x12 0x12 0x12 0x12] :u8)) :u32)]
    (is (= 1234 (a/value integer)))
    (is (= 0x12121212 (a/value bits))))
  (with-open [pointer (let [bytes (a/array [0x12 0x12 0x12 0x12] {:align 4} :u8)]
                        (k/as (k/ptrCast (k/& bytes)) [:*const :u32]))]
    (is (seq (:owners (value/realize! pointer))))
    (is (= 0x12121212 (a/value @pointer))))
  (with-open [bytes (a/array [1 2 3 4] :u8)
              pointer (k/as (k/ptrCast (k/alignCast (k/& bytes))) [:*const :u32])]
    (is (= [:*const :u32] (value/qualified-type pointer))))
  (is (thrown? clojure.lang.ExceptionInfo (a/array [1 2] {:align 3} :u8)))
  (is (false? (boolean (value/error-bearing-schema? nil))))
  (is (true? (boolean (value/error-bearing-schema?
                       {:kind :array :element-schema {:kind :error-union}})))))
