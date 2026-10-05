(ns aguafria.zig.discovery-comptime-construction-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defstruct Command
  [[:name [:slice-const :u8]]
   [:function [:fn {} [{:type :i32}] :i32]]])

(a/defn- increment :i32 [[n :i32]]
  (k/+ n 1))

(a/defconst commands
  (a/array [(Command {:name "increment" :function increment})] Command))

(a/defn apply-single :i32 [[n :i32]]
  (let [command (Command {:name "increment" :function increment})]
    ((:function command) n)))

(a/defn apply-first :i32 [[n :i32]]
  ((:function (a/get commands 0)) n))

(a/deftest constructor-bodies
  (let [command (Command {:name "increment" :function increment})]
    (try (testing/expectEqual 42 ((:function command) 41))))
  (let [items (a/array [(Command {:name "increment" :function increment})] Command)]
    (try (testing/expectEqual 42 ((:function (a/get items 0)) 41)))))
