(ns aguafria.zig.jvm-type-arguments-test
  (:require [aguafria.keyword :as k]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as a]
            [aguafria.zig.emitter :as emitter]
            [clojure.test :refer [deftest is]]))

(deftest schemas-work-directly-in-type-argument-positions
  (is (= 42 (a/value (k/as 42 :i32))))
  (is (= [1 2 3]
         (a/value (k/as (a/array [1 2 3] :u8) [:array 3 :u8]))))
  (is (= (a/value (k/sizeOf [:optional [:* :i32]]))
         (a/value (k/sizeOf [:* :i32]))))
  (is (true? (a/value (mem/eql :u8 "hi" "hi"))))
  (is (= "u32" (get-in (a/value (k/typeInfo [:* :u32]))
                       [:pointer :child :type]))))

(deftest native-emission-recognizes-type-parameters-from-zig-signatures
  (doseq [[form expected]
          [['(k/sizeOf [:optional [:* :i32]]) "@sizeOf(?*i32)"]
           ['(k/typeInfo [:* :u32]) "@typeInfo(*u32)"]
           ['(k/alignOf [:array 3 :u32]) "@alignOf([3]u32)"]
           ['(k/as [1 2] [:array 2 :i32]) "@as([2]i32, .{1, 2})"]]]
    (is (= expected (emitter/emit-expr (the-ns 'aguafria.zig.jvm-type-arguments-test) form)))))

(a/defn List :type [[T {:attrs #{k/comptime}} :type]]
  (a/struct [[:item T]]))

(a/defn direct-schema-size :usize []
  (k/sizeOf (List [:* :u8])))

(deftest user-and-imported-functions-recognize-declared-type-parameters
  (let [context (the-ns 'aguafria.zig.jvm-type-arguments-test)]
    (is (= "List(*u8)" (emitter/emit-expr context '(List [:* :u8]))))
    (is (= "List(*u8)"
           (emitter/emit-expr context (emitter/qualify-form context '(List [:* :u8])))))
    (is (re-find #"eql\(\[2\]u8,"
                 (emitter/emit-expr context '(mem/eql [:array 2 :u8] left right))))
    (is (= (a/value (k/sizeOf [:* :u8])) (a/value (direct-schema-size))))
    (is (= (a/value (k/sizeOf [:* :u8])) (a/value (k/sizeOf (List [:* :u8])))))))
