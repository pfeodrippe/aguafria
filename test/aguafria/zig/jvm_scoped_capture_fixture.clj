(ns aguafria.zig.jvm-scoped-capture-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest block-propagates-errors
  (k/comptime
   (a/block
    (try (testing/expectEqual 4 (k/i32 4)))
    (try (testing/expectEqual 5 (k/i32 5))))))

(a/deftest error-and-payload-captures
  (let [source (k/var 7 [:error-union [:error-set [:Rejected]] :u32])
        bias (k/u32 3)
        choice (a/if-capture {:payload [item] :error [err]} source
                             (k/+ item bias)
                             (k/switch err
                                       (case [(a/error-value :Rejected)] nil)))
        recovered (a/catch-capture [err] source
                                   (k/switch err
                                             (case [(a/error-value :Rejected)] nil)))]
    (k/= :_ (k/& source))
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
