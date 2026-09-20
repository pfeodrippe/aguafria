(ns learn.example.test-pointer-coerce-const-optional
  (:require [aguafria.keyword :as ak]
            [aguafria.std.mem :as mem]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest optional-sentinel-pointer-coercion-test
  (let [window-names (az/array-init [:array 1 [:sentinel-const :u8 0]]
                                    ["window name"])
        optional-names (ak/as (& window-names) [:slice-const [:optional [:sentinel-const :u8 0]]])]
    (try (testing/expectEqualStrings
          "window name"
          (mem/span (az/unwrap (az/index optional-names 0)))))))

(comment
  (optional-sentinel-pointer-coercion-test))
