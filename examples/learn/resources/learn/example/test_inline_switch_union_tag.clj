(ns learn.example.test-inline-switch-union-tag
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst U
  (az/union {:attrs #{k/enum}}
            [[:a :u32]
             [:b :f32]]))

(az/defn- getNum :u32 [[u U]]
  (switch u
    ;; Here `num` is a runtime-known value that is either
    ;; `u.a` or `u.b` and `tag` is `u`'s comptime-known tag value.
          (az/inline-case-else [num tag]
                               (if (k/== tag :.b)
                                 (k/intFromFloat num)
                                 num))))

(az/deftest test
  (let [u (U {:b 42})]
    (try (testing/expectEqual 42 (getNum u)))))

(comment
  (test))
