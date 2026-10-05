(ns aguafria.zig.discovery-scoped-context-fixture
  (:require [aguafria.keyword :as k]
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
  (k/const choice Choice
           (a/with-block :result
             (k/break :result (:.first input))))
  (:value choice))

(a/defn read-value :i32
  [[choice Choice]]
  (:value choice))

(a/defn typed-cast :i32
  [[input :i32]]
  (let [choice (k/as (a/with-block :result
                       (k/break :result (:.second input)))
                     Choice)]
    (:value choice)))

(a/defn propagates :!Choice
  [[input :i32]]
  (k/const choice Choice
           (a/with-block :result
             (try (testing/expectEqual input input))
             (k/break :result (:.first input))))
  choice)

(a/defenum Status [:first :second])

(a/defn typed-enum :usize
  [[input :i32]]
  (k/const selected Status
           (a/with-block :pick
             (when (k/> input 10)
               (k/break :pick :.second))
             (k/break :pick :.first)))
  (k/intFromEnum selected))

(a/deftest input-construction
  (let [input (k/i32 4)]
    (k/= :_ input)))
