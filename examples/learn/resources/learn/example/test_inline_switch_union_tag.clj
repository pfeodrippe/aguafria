(ns learn.example.test-inline-switch-union-tag
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst U
  (az/union {:attrs #{k/enum}}
    [[:a :u32]
     [:b :f32]]))

(az/defn- getNum :u32 [[value U]]
  (switch value
    (az/inline-case-else [number tag]
      (if (k/== tag :.b)
        (k/intFromFloat number)
        number))))

(az/deftest inline-union-tag-test
  (let [value (U {:b 42})]
    (try (testing/expectEqual 42 (getNum value)))))

(comment
  (inline-union-tag-test))
