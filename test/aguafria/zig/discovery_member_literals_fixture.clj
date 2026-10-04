(ns aguafria.zig.discovery-member-literals-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defstruct Text
  [(a/fn length :usize
     [[text {:attrs #{k/comptime}} [:slice-const :u8]]]
     (:len text))])

(a/deftest never-run
  (k/= :_ ((:length Text) "hello"))
  (k/= :_ ((:length Text) (k/++ "he" "llo")))
  (k/= :_ ((:length Text) (k/++ (k/++ "good" " ") "bye")))
  (k/unreachable))
