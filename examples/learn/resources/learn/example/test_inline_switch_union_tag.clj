(ns learn.example.test-inline-switch-union-tag
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst U
  (az/union {:attrs #{:enum}}
    [[:a :u32]
     [:b :f32]]))

(az/defn- getNum :u32 [[value U]]
  (switch value
    (az/inline-case-else [number tag]
      (if (== tag :.b)
        (ak/intFromFloat number)
        number))))

(az/deftest inline-union-tag-test
  (let [value (az/init {:b 42} U)]
    (try (testing/expectEqual 42 (getNum value)))))

(comment
  (inline-union-tag-test))
