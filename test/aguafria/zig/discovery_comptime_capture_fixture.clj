(ns aguafria.zig.discovery-comptime-capture-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defstruct Command
  [[:function [:fn {} [{:type :i32}] :i32]]])

(a/defn- increment :i32 [[n :i32]]
  (+ n 1))

(a/defn- increment-twice :i32 [[n :i32]]
  (+ n 2))

(a/defconst commands
  (a/array [(Command {:function increment})
            (Command {:function increment-twice})] Command))

(a/defn select-command :i32
  [[index {:attrs #{comptime}} :usize] [n :i32]]
  ((:function (a/get commands index)) n))

(a/deftest selected-commands
  (try (testing/expectEqual 42 (select-command 0 41)))
  (try (testing/expectEqual 43 (select-command 1 41))))
