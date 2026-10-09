(ns aguafria.zig.jvm-literal-precision-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.runtime :as runtime]
            [clojure.test :refer [deftest is]]))

;; Each decimal lies just above a rounding midpoint. Parsing it as a JVM
;; double before applying the Zig type loses the digits that decide rounding.
(deftest source-literals-round-in-zig
  (doseq [[literal type expected]
          [["1.00048828125000000000000001" :f16 1.0009765625]
           ["1.0000000596046448" :f32 1.0000001192092896]
           ["1.000000000000000111022302462516" :f64 1.0000000000000002]]]
    (let [number (a/number-literal literal)
          result (k/as number type)
          computed (k/as (k/+ number (a/number-literal "0.0")) type)]
      (is (true? (a/value (k/== result (k/as expected type)))))
      (is (true? (a/value (k/== computed (k/as expected type)))))))
  (is (true? (a/value
              (k/== (k/as (k/- (a/number-literal "1.0000000596046448")) :f32)
                    (k/as -1.0000001192092896 :f32))))))

(deftest integer-literals-stay-exact-and-range-checked
  (is (= 18446744073709551615N
         (a/value (k/as (a/number-literal "0xffff_ffff_ffff_ffff") :u64))))
  ;; Once the concrete u8 constructor exists, range rejection happens at the
  ;; ordinary ABI boundary rather than by compiling a new literal handler.
  (is (= 255 (a/value (k/as (a/number-literal "255") :u8))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"out of range"
                       (k/as (a/number-literal "256") :u8))))

(deftest jvm-coercion-agrees-with-a-native-declaration
  (binding [runtime/*source-only-registration?* true]
    (a/defn native-rounding :f32 []
      (a/number-literal "1.0000000596046448")))
  (is (= (a/value (native-rounding))
         (a/value (k/as (a/number-literal "1.0000000596046448") :f32)))))
