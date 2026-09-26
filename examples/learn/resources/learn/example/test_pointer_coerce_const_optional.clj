(ns learn.example.test-pointer-coerce-const-optional
  (:require [aguafria.keyword :as k]
            [aguafria.std.mem :as mem]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest cast-*one-sentinel-u8-to-const-optional-sentinel-u8-slice
  (let [window-name (az/init ["window name"] [:array 1 [:sentinel-const :u8 0]])
        x (k/as (k/& window-name) [:slice-const [:optional [:sentinel-const :u8 0]]])]
    (try (testing/expectEqualStrings
          "window name"
          (mem/span (az/unwrap (az/get x 0)))))))

(comment
  (cast-*one-sentinel-u8-to-const-optional-sentinel-u8-slice))
