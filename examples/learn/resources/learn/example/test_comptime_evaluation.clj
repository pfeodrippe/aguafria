(ns learn.example.test-comptime-evaluation
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defstruct CmdFn
  [[:name [:slice-const :u8]]
   [:func [:fn {} [{:type :i32}] :i32]]])

(a/defn- one :i32
  [[value :i32]]
  (k/+ value 1))

(a/defn- two :i32
  [[value :i32]]
  (k/+ value 2))

(a/defn- three :i32
  [[value :i32]]
  (k/+ value 3))

(a/defconst cmd-fns
  (a/array [(CmdFn {:name "one" :func one})
            (CmdFn {:name "two" :func two})
            (CmdFn {:name "three" :func three})] CmdFn))

(a/defn- performFn :i32
  [[prefix-char {:attrs #{k/comptime}} :u8] [start-value :i32]]
  (let [result (k/var start-value :i32)
        i (k/var 0 nil {:attrs #{k/comptime}})]
    (a/while-loop {:inline? true
                   :continue (a/assign-expr "+=" i 1)}
                  (k/< i (:len cmd-fns))
                  (when (k/== (a/get-in cmd-fns [i :name 0]) prefix-char)
                    (k/= result ((a/get-in cmd-fns [i :func]) result))))
    result))

(a/deftest perform-fn
  (try (testing/expectEqual 6 (performFn \t 1)))
  (try (testing/expectEqual 1 (performFn \o 0)))
  (try (testing/expectEqual 99 (performFn \w 99))))

(comment
  (perform-fn))
