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

(deftest characters-do-not-discard-explicit-native-operand-types
  (let [operand (value/native-value {:kind :const :type :u32}
                                    (constantly {:representation :scalar :value 7}))
        syntax {:kind :operator :zig-token "+"}]
    (is (= [:u32 :u32] (:types (handlers/operator-plan syntax [operand \a]))))
    (is (= 97 (second (:arguments (handlers/operator-plan syntax [operand \a])))))))
