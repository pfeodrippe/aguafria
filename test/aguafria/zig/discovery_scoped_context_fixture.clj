(ns aguafria.zig.discovery-scoped-context-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defstruct Choice
  [[:value :i32]
   (a/fn first Choice [[input :i32]]
     (Choice {:value input}))
   (a/fn second Choice [[input :i32]]
     (Choice {:value input}))])

(a/defn typed-local :i32
  [[input :i32]]
  (const choice Choice
         (a/with-block :result
           (break :result ((a/enum-literal ".first") input))))
  (:value choice))

(a/defn read-value :i32
  [[choice Choice]]
  (:value choice))

(a/defn typed-cast :i32
  [[input :i32]]
  (let [choice (as (a/with-block :result
                     (break :result ((a/enum-literal ".second") input)))
                   Choice)]
    (:value choice)))

(a/defn propagates :!Choice
  [[input :i32]]
  (const choice Choice
         (a/with-block :result
           (try (testing/expectEqual input input))
           (break :result ((a/enum-literal ".first") input))))
  choice)

(a/defenum Status [:first :second])

(a/defn typed-enum :usize
  [[input :i32]]
  (const selected Status
         (a/with-block :pick
           (when (> input 10)
             (break :pick :second))
           (break :pick :first)))
  (intFromEnum selected))

(a/deftest input-construction
  (let [input (i32 4)]
    (= :_ input)))
