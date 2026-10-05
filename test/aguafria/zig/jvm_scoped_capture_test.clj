(ns aguafria.zig.jvm-scoped-capture-test
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]
            [aguafria.zig.emitter :as emitter]
            [clojure.test :refer [deftest is]]))

(deftest scoped-control-forms-preserve-native-bindings
  (with-open [source (k/var 7 [:error-union [:error-set [:Rejected]] :u32])
              bias (k/u32 3)]
    (is (= 10 (a/value (a/if-capture {:payload [item] :error [err]} source
                                     (k/+ item bias)
                                     (k/switch err
                                               (case [(a/error-value :Rejected)] 0))))))
    (is (= 7 (a/value (a/catch-capture [err] source
                                       (k/switch err
                                                 (case [(a/error-value :Rejected)] 0))))))
    (is (= 10 (a/value (a/unwrap
                        (a/if-capture {:payload [item] :error [err]} source
                                      (k/+ item bias)
                                      (k/switch err
                                                (case [(a/error-value :Rejected)] nil)))))))
    (k/= source (a/error-value :Rejected))
    (is (= 31 (a/value (a/catch-capture [err] source
                                        (k/switch err
                                                  (case [(a/error-value :Rejected)] 31))))))
    (is (= 29 (a/value (a/if-capture {:payload [item] :error [err]} source
                                     (k/+ item bias)
                                     (k/switch err
                                               (case [(a/error-value :Rejected)] 29)))))))
  (with-open [target (k/var 0 :i32)]
    (a/if-capture-stmt {:payload [item]} (k/as 11 [:optional :i32])
                       (k/= target item))
    (is (= 11 (a/value target))))
  (is (= #{'source}
         (set (emitter/scoped-captures
               (the-ns 'aguafria.zig.jvm-scoped-capture-test)
               '(a/if-capture {:payload [bias] :error [source]} source
                              (k/+ bias 1) source)
               '[source bias])))))

(deftest native-blocks-preserve-try-and-mutable-captures
  (with-open [counter (k/var 0 :i32)]
    (is (= {:ok nil}
           (a/value (a/block
                     (k/+= counter 1)
                     (try (testing/expectEqual 1 counter))
                     (k/+= counter 1)
                     (try (testing/expectEqual 2 counter))))))
    (is (= 2 (a/value counter)))
    (let [result (a/block
                  (try (testing/expectEqual 99 counter))
                  (k/+= counter 10))]
      (is (= "TestExpectedEqual" (get-in (a/value result) [:error :name])))
      (is (= 2 (a/value counter))))))
