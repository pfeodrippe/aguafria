(ns aguafria.zig.comptime-macro-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn phase-answer :i32 [[n :i32]]
  (if (inComptime) (+ n 1) 0))

;; Discovery prepares this exact inner expression without invoking the body.
(a/defn prepared-site :void []
  (= :_ (comptime (phase-answer 41))))

(comment
  (a/value (comptime (phase-answer 41))))
