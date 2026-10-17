(ns aguafria.zig.discovery-scoped-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn read-only :i32
  [[input :i32]]
  (a/with-block :result
    (break :result (+ input 1))))

(a/defn mutable :i32
  [[input :i32]]
  (let [counter (k/var input :i32)]
    (= :_ (a/with-block :result
            (+= counter 3)
            (break :result counter)))
    counter))

(a/defn shadowed :i32
  [[input :i32]]
  (a/with-block :result
    (let [input (+ input 2)]
      (break :result input))))

(a/defn propagates :!i32
  [[input :i32]]
  (a/with-block :result
    (try (testing/expectEqual input input))
    (break :result input)))

(a/defconst closed :i32
  (a/with-block :result
    (let [input 5]
      (break :result input))))

(a/deftest input-construction
  (let [input (i32 4)]
    (= :_ input)))
