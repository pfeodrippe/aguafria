(ns aguafria.zig.handlers-test
  (:require [aguafria.zig.handlers :as handlers]
            [aguafria.zig.value :as value]
            [clojure.test :refer [deftest is]]))

(deftest character-operands-use-compiler-confirmed-integer-plans
  (doseq [operation ["+" "*" "==" "<"]]
    (let [syntax {:kind :operator :zig-token operation}]
      (is (= (handlers/operator-signature-plan
              syntax [{:literal 955 :type :comptime_int}
                      {:literal 2 :type :comptime_int}])
             (handlers/operator-plan syntax [\λ 2]))))))

(deftest source-integers-use-lossless-carriers-but-source-floats-do-not
  (doseq [n [101 128175 (bigint (.shiftLeft java.math.BigInteger/ONE 300))]]
    (let [operand (value/native-value
                   {:kind :const :type :comptime_int}
                   (constantly {:representation :scalar :value n
                                :expression (list 'number-literal (str n))}))
          syntax {:kind :operator :zig-token "=="}]
      (is (= (handlers/operator-signature-plan
              syntax [{:literal n :type :comptime_int}
                      {:literal 2 :type :comptime_int}])
             (handlers/operator-plan syntax [operand 2])))))
  (let [operand (value/native-value
                 {:kind :const :type :comptime_float}
                 (constantly {:representation :scalar :value 1.0
                              :expression '(number-literal "1.00000000000000000001")}))]
    (is (nil? (handlers/operator-plan {:kind :operator :zig-token "=="} [operand 1.0])))))

(deftest characters-do-not-discard-explicit-native-operand-types
  (let [operand (value/native-value {:kind :const :type :u32}
                                    (constantly {:representation :scalar :value 7}))
        syntax {:kind :operator :zig-token "+"}]
    (is (= [:u32 :u32] (:types (handlers/operator-plan syntax [operand \a]))))
    (is (= 97 (second (:arguments (handlers/operator-plan syntax [operand \a])))))))
