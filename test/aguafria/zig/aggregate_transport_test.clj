(ns aguafria.zig.aggregate-transport-test
  (:require [aguafria.keyword :as k]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as a]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.runtime :as runtime]
            [clojure.test :refer [deftest is]]))

(deftest large-native-arrays-do-not-unroll-inspection-loops
  (is (= :prepared (:status (jvm/precompile-storage!
                             {:kind :address :receiver [:array 2048 :u32]})))))

(deftest explicit-type-arguments-are-stable-under-qualification
  (let [context (the-ns 'aguafria.zig.aggregate-transport-test)]
    (doseq [form ['(mem/zeroes :u32)
                  '(mem/zeroes (a/type [:array 4 :u8]))]]
      (let [qualified (emitter/qualify-form context form)]
        (is (= qualified (emitter/qualify-form context qualified)))
        (is (= (emitter/emit-expr context form)
               (emitter/emit-expr context qualified)))))))

(deftest struct-array-and-vector-fields-cross-the-jvm-boundary
  (let [namespace (create-ns (symbol (str "aguafria.aggregate-transport-" (random-uuid))))]
    (try
      (binding [*ns* namespace runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (eval '(a/defstruct Packet
                 [[:bytes [:array 4 :u8]]
                  [:lanes [:vector 2 :i32]]])))
      (let [packet-type (var-get (ns-resolve namespace 'Packet))
            packet (mem/zeroes packet-type)]
        (is (= {:bytes [0 0 0 0] :lanes [0 0]} (a/value packet)))
        (is (= [0 0 0 0] (a/value (:bytes packet))))
        (is (= [0 0] (a/value (:lanes packet))))
        (let [mutable (k/var packet)]
          (k/= (a/get (:bytes mutable) 2) 7)
          (is (= [0 0 7 0] (a/value (:bytes mutable))))
          (is (= [0 0 0 0] (a/value (:bytes packet))))))
      (finally
        (remove-ns (ns-name namespace))))))
