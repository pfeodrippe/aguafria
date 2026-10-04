(ns aguafria.zig.discovery-scoped-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn read-only :i32
  [[input :i32]]
  (a/with-block :result
    (k/break :result (k/+ input 1))))

(a/defn mutable :i32
  [[input :i32]]
  (let [counter (k/var input :i32)]
    (k/= :_ (a/with-block :result
              (k/+= counter 3)
              (k/break :result counter)))
    counter))

(a/defn shadowed :i32
  [[input :i32]]
  (a/with-block :result
    (let [input (k/+ input 2)]
      (k/break :result input))))

(a/defn propagates :!i32
  [[input :i32]]
  (a/with-block :result
    (try (testing/expectEqual input input))
    (k/break :result input)))

(a/defconst closed :i32
  (a/with-block :result
    (let [input 5]
      (k/break :result input))))

(a/deftest input-construction
  (let [input (k/i32 4)]
    (k/= :_ input)))
