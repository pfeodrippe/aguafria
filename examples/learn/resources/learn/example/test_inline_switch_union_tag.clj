(ns learn.example.test-inline-switch-union-tag
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defunion U {:attrs #{enum}}
  [[:a :u32]
   [:b :f32]])

(a/defn- getNum :u32 [[u U]]
  (switch u
    ;; Here `num` is a runtime-known value that is either
    ;; `u.a` or `u.b` and `tag` is `u`'s comptime-known tag value.

    (inline (a/with-block :_ [num tag] (if (== tag :b)
                                         (intFromFloat num)
                                         num)))))

(a/deftest test
  (let [u (U {:b 42})]
    (k/try (testing/expectEqual 42 (getNum u)))))

(comment
  (test))
