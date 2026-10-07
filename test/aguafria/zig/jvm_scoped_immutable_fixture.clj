(ns aguafria.zig.jvm-scoped-immutable-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn runtime-result [:error-union [:error-set [:Rejected]] :u32]
  [[reject :bool]]
  (if reject (a/error-value :Rejected) 0))

(a/defn overwrite-error :void
  [[value [:* [:error-union [:error-set [:Rejected]] :u32]]]]
  (k/= @value (a/error-value :Rejected)))

(a/deftest successful-constant
  (let [value (k/as 0 [:error-union :anyerror :u32])]
    (a/if-capture-stmt {:payload [item] :error [err]} value
                       (try (testing/expectEqual item 0))
                       (a/block
                        (k/= :_ err)
                        (k/unreachable)))))

(a/deftest runtime-error-is-not-a-retained-constant
  (let [value (runtime-result true)]
    (a/if-capture-stmt {:payload [item] :error [err]} value
                       (try (testing/expectEqual item 0))
                       (try (testing/expectEqual err (a/error-value :Rejected))))))

(a/deftest mutable-error-is-not-a-retained-constant
  (let [value (k/var 0 [:error-union [:error-set [:Rejected]] :u32])]
    (k/= value (a/error-value :Rejected))
    (a/if-capture-stmt {:payload [item] :error [err]} value
                       (try (testing/expectEqual item 0))
                       (try (testing/expectEqual err (a/error-value :Rejected))))))

(a/deftest mutable-address-error-is-not-a-retained-constant
  (let [value (k/var 0 [:error-union [:error-set [:Rejected]] :u32])
        alias (k/& value)]
    (overwrite-error alias)
    (a/if-capture-stmt {:payload [item] :error [err]} value
                       (try (testing/expectEqual item 0))
                       (try (testing/expectEqual err (a/error-value :Rejected))))))

(a/deftest successful-closed-error-constant
  (let [value (k/as 0 [:error-union [:error-set [:Rejected]] :u32])]
    (a/if-capture-stmt {:payload [item] :error [err]} value
                       (try (testing/expectEqual item 0))
                       (a/block
                        (k/= :_ err)
                        (k/unreachable)))))

(a/deftest successful-optional-constant
  (let [value (k/as 0 [:error-union :anyerror [:optional :u32]])]
    (a/if-capture-stmt {:payload [item] :error [err]} value
                       (try (testing/expectEqual (a/unwrap item) 0))
                       (a/block
                        (k/= :_ err)
                        (k/unreachable)))))
