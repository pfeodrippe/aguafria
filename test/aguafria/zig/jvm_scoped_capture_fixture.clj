(ns aguafria.zig.jvm-scoped-capture-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest block-propagates-errors
  (comptime
   (a/block
    (try (testing/expectEqual 4 (i32 4)))
    (try (testing/expectEqual 5 (i32 5))))))

(a/deftest error-and-payload-captures
  (let [source (k/var 7 [:error-union [:error-set [:Rejected]] :u32])
        bias (u32 3)
        choice (a/if-capture {:payload [item] :error [err]} source
                             (+ item bias)
                             (switch err
                               (a/error-value :Rejected) nil))
        recovered (a/catch-capture [err] source
                                   (switch err
                                     (a/error-value :Rejected) nil))]
    (= :_ (k/& source))
    (try (testing/expectEqual 10 (a/unwrap choice)))
    (try (testing/expectEqual 7 (a/unwrap recovered)))))

(a/defn- empty-or-slice [:error-union :anyerror [:slice :u8]]
  [[empty? :bool] [bytes [:slice :u8]]]
  (if empty?
    (k/& (a/array [] :u8))
    (a/slice bytes 0 1)))

(a/deftest try-carrier-reflection
  (let [bytes (k/var (a/deref "hi"))
        slice (a/slice bytes 0)]
    (try (testing/expectEqual 0 (:len (k/try (empty-or-slice true slice)))))
    (try (testing/expectEqual 1 (:len (k/try (empty-or-slice false slice)))))))
