(ns learn.example.test-pointer-coerce-const-optional
  (:require [aguafria.std.mem :as mem]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest optional-sentinel-pointer-coercion-test
  (let [window-names (az/array-init [:array 1 [:sentinel-const :u8 0]]
                                    ["window name"])
        ^{:zig/type [:slice-const [:optional [:sentinel-const :u8 0]]]}
        optional-names (& window-names)]
    (try (testing/expectEqualStrings
           "window name"
           (mem/span (az/unwrap (az/index optional-names 0)))))))
