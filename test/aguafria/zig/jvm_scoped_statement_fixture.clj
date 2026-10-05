(ns aguafria.zig.jvm-scoped-statement-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defconst Failure (a/type [:error-set [:Stop]]))

(a/defn- stop [:error-union Failure :bool] []
  (:Stop Failure))

(a/defn- inline-count :i32
  [[limit {:attrs #{k/comptime}} :u32] [start :i32]]
  (let [result (k/var start :i32)
        i (k/var 0 nil {:attrs #{k/comptime}})]
    (a/while-loop {:inline? true :continue (a/assign-expr "+=" i 1)}
                  (k/< i limit)
                  (k/+= result 1))
    result))

(a/defn comptime-loop-capture :!void []
  (try (testing/expectEqual 3 (inline-count 3 0))))

(a/defn contextual-switch :!void []
  (let [selector (k/u8 0)
        wide (k/u64 73)]
    (try (testing/expectEqual
          73
          (k/as (a/switch selector
                          (case [0] (k/intCast wide))
                          (case-else (k/intCast wide)))
                :u8)))))

(a/defn label-value-collision :!void []
  (let [vm (k/var 7 :u8)
        selector (k/u8 0)]
    (k/= :_ (k/& vm))
    (try (testing/expectEqual
          7
          (a/labeled-switch vm selector
                            (case [0] (k/continue vm 1))
                            (case [1] vm)
                            (case-else 0))))))

(a/defn while-error-capture :!void []
  (try (testing/expectEqual
        (:Stop Failure)
        (a/while-loop {:error [err] :else-expression err}
                      (stop)
                      (k/unreachable)))))
