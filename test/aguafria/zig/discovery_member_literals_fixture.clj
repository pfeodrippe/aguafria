(ns aguafria.zig.discovery-member-literals-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defstruct Text
  [(a/fn length :usize
     [[text {:attrs #{comptime}} [:slice-const :u8]]]
     (:len text))])

(a/deftest never-run
  (= :_ ((:length Text) "hello"))
  (= :_ ((:length Text) (++ "he" "llo")))
  (= :_ ((:length Text) (++ (++ "good" " ") "bye")))
  (unreachable))
