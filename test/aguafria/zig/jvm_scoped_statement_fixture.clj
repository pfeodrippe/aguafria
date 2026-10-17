(ns aguafria.zig.jvm-scoped-statement-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defconst Failure (a/type [:error-set [:Stop]]))

(a/defn- stop [:error-union Failure :bool] []
  (:Stop Failure))

(a/defn- inline-count :i32
  [[limit {:attrs #{comptime}} :u32] [start :i32]]
  (let [result (k/var start :i32)
        i (k/var 0 nil {:attrs #{:comptime}})]
    (a/while-loop {:inline? true :continue (a/assign-expr "+=" i 1)}
                  (< i limit)
                  (+= result 1))
    result))

(a/defn comptime-loop-capture :!void []
  (try (testing/expectEqual 3 (inline-count 3 0))))

(a/defn contextual-switch :!void []
  (let [selector (u8 0)
        wide (u64 73)]
    (try (testing/expectEqual
          73
          (as (switch selector
                0 (intCast wide)
                (intCast wide))
              :u8)))))

(a/defn label-value-collision :!void []
  (let [vm (k/var 7 :u8)
        selector (u8 0)]
    (= :_ (k/& vm))
    (try (testing/expectEqual
          7
          (switch [vm selector]
            0 (continue vm 1)
            1 vm
            0)))))

(a/defn while-error-capture :!void []
  (try (testing/expectEqual
        (:Stop Failure)
        (a/while-loop {:error [err] :else-expression err}
                      (stop)
                      (unreachable)))))

(a/defn for-else-value :!void []
  (let [items (a/array [1 2] :i32)
        sum (k/var 0 :i32)
        result (for [item items]
                 (+= sum item)
                 (a/else-expression
                  (a/with-block :done
                    (try (testing/expectEqual 3 sum))
                    (break :done sum))))]
    (try (testing/expectEqual :i32 (TypeOf result)))
    (try (testing/expectEqual 3 result))))
