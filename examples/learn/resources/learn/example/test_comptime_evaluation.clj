(ns learn.example.test-comptime-evaluation
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defstruct CmdFn
  [[:name [:slice-const :u8]]
   [:func [:fn {} [{:type :i32}] :i32]]])

(az/defconst cmd-fns
  (az/array-init [(CmdFn {:name "one" :func one})
                  (CmdFn {:name "two" :func two})
                  (CmdFn {:name "three" :func three})] [:array :_ CmdFn]))

(az/defn- one :i32
  [[value :i32]]
  (+ value 1))

(az/defn- two :i32
  [[value :i32]]
  (+ value 2))

(az/defn- three :i32
  [[value :i32]]
  (+ value 3))

(az/defn- perform-fn :i32
  [[prefix-char {:zig/prefix "comptime"} :u8] [start-value :i32]]
  (let [result (ak/var start-value :i32)
        i (ak/var 0 nil {:zig/prefix "comptime"})]
    (az/while-loop {:inline? true
                    :continue (az/assign-expr "+=" i 1)}
      (< i (az/field cmd-fns :len))
      (let [command (az/index cmd-fns i)]
        (when (== (az/index (az/field command :name) 0) prefix-char)
          (ak/= result ((az/field command :func) result)))))
    result))

(az/deftest perform-functions-test
  (try (testing/expectEqual 6 (perform-fn \t 1)))
  (try (testing/expectEqual 1 (perform-fn \o 0)))
  (try (testing/expectEqual 99 (perform-fn \w 99))))

(comment
  (perform-functions-test))
