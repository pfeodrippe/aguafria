(ns aguafria.zig.discovery-runtime-tuple-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/deftest never-run
  (let [result (k/var (k/mulWithOverflow (k/u64 12) (k/u64 10)))]
    (k/= result (k/addWithOverflow (a/get result 0) (k/u64 3)))
    (k/= result [(k/u64 99) (k/u1 0)])
    (debug/print "{} {}" result)
    (k/= :_ (k/& result))
    (k/= :_ (a/get result 0))
    (k/= :_ (a/get result 1)))
  (k/unreachable))

(a/defn show-name :void
  [[input [:slice-const :u8]]]
  (let [text (k/as input [:slice-const :u8])
        tuple (k/++ [text] [])]
    (debug/print "{s}" tuple)))

(a/defn nested-flags :void
  [[input :bool]]
  (let [flag (k/bool input)
        inner (k/++ [flag] [])
        outer (k/++ [inner] [false])]
    (k/= :_ outer)))
