(ns aguafria.zig.comptime-macro-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.emitter :as emitter]
            [clojure.test :refer [deftest is]]))

(a/defn native-answer :i32 [[n :i32]]
  (if (k/inComptime) (k/+ n 1) 0))

(deftest comptime-captures-a-whole-native-expression
  (is (:macro (meta #'k/comptime)))
  (is (:aguafria/scoped? (meta #'k/comptime)))
  (is (= 20 (a/value (k/comptime (let [x (k/+ 2 3)] (k/* x 4))))))
  ;; Neither the callable's JVM wrapper nor an inner operator's JVM handler runs.
  (with-redefs [native-answer (fn [& _] (throw (Exception. "JVM wrapper ran")))
                k/+ (fn [& _] (throw (Exception. "JVM operator ran")))]
    (is (= 42 (a/value (k/comptime (native-answer (k/+ 40 1)))))))
  (is (false? (a/value (k/comptime (k/and false (k/compileError "unreachable")))))))

(deftest comptime-reuses-native-result-types-and-context
  (is (k/== (a/type :u16) (k/comptime (k/TypeOf (k/as 7 :u16)))))
  (with-open [result (k/as (k/comptime (k/intCast 17)) :i32)]
    (is (= 17 (a/value result))))
  (is (= 1 (a/value (k/comptime (k/+% (k/as 255 :u8) 2))))))

(deftest comptime-captures-lexical-values-without-promoting-runtime-storage
  (let [text "hello"]
    (is (= 5 (a/value (k/comptime (:len text))))))
  (with-open [counter (k/var 3 :i32)]
    (is (thrown? Exception (k/comptime (k/+ counter 1))))
    (is (= 3 @counter)))
  (with-open [counter (k/var 3 nil {:attrs #{:comptime}})]
    (is (= 5 (a/value (k/comptime (let [next (k/+ counter 2)]
                                    (k/= counter next)
                                    next)))))
    (is (= 5 @counter))
    (let [result (k/comptime (do (k/+= counter 2)))]
      (is (nil? (a/value result)))
      (is (k/== (a/type :void) (k/TypeOf result))))
    (is (= 7 @counter))
    (is (thrown? Exception
                 (k/comptime (do (k/+= counter 1) (k/compileError "no writeback")))))
    (is (= 7 @counter))))

(deftest comptime-writebacks-preserve-result-context-and-owned-results
  (with-open [counter (k/var 3 nil {:attrs #{:comptime}})
              result (k/as (k/comptime (k/intCast counter)) :u16)]
    (is (= 3 (a/value result)))
    (is (k/== (a/type :u16) (k/TypeOf result)))
    (is (= 3 @counter)))
  (let [result (with-open [counter (k/var 3 nil {:attrs #{:comptime}})]
                 (k/comptime
                  (let [next (k/+ counter 1)]
                    (k/= counter next)
                    (a/array [next next] :i32))))]
    (with-open [owned result]
      (is (= [4 4] (a/value owned)))
      (is (k/== (a/type [:array 2 :i32]) (k/TypeOf owned))))))

(deftest comptime-rejects-host-operators-and-invalid-native-programs
  (binding [*ns* (the-ns 'aguafria.zig.comptime-macro-test)]
    (doseq [form '[(k/comptime (clojure.core/+ 1 2))
                   (k/comptime (clojure.core/* 2 3))
                   (k/comptime (clojure.core/and true false))
                   (k/comptime)
                   (k/comptime 1 2)]]
      (is (thrown? Exception (macroexpand form)))))
  (is (thrown? Exception (k/comptime (k/compileError "comptime rejection")))))

(deftest comptime-stays-native-inside-captured-declarations
  (let [context (the-ns 'aguafria.zig.comptime-macro-test)]
    (is (= "(comptime (1 + 2))"
           (emitter/emit-expr (emitter/qualify-form context '(k/comptime (k/+ 1 2))))))))
