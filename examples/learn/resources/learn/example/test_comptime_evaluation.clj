(ns learn.example.test-comptime-evaluation
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defstruct Command
  [[:name [:slice-const :u8]]
   [:func [:fn {} [{:type :i32}] :i32]]])

(az/defconst commands
  (az/array-init [:array _ Command]
                 [(Command {:name "one" :func one})
                  (Command {:name "two" :func two})
                  (Command {:name "three" :func three})]))

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
  (let [^{:var :i32} result start-value
        ^{:var true :zig/prefix "comptime"} index 0]
    (az/while-loop {:inline? true
                    :continue (az/assign-expr "+=" index 1)}
      (< index (az/field commands :len))
      (let [command (az/index commands index)]
        (when (== (az/index (az/field command :name) 0) prefix-char)
          (set! result ((az/field command :func) result)))))
    result))

(az/deftest perform-functions-test
  (try (testing/expectEqual 6 (perform-fn \t 1)))
  (try (testing/expectEqual 1 (perform-fn \o 0)))
  (try (testing/expectEqual 99 (perform-fn \w 99))))

(comment
  (perform-functions-test))
