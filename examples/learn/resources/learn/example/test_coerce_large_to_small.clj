(ns learn.example.test-coerce-large-to-small
  (:require aguafria.std
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest comptime-narrowing-test
  ;; The compiler knows this particular u64 value fits in u8.
  (let [^{:zig/type :u64} wide 255
        ^{:zig/type :u8} narrow wide]
    (try (testing/expectEqual 255 narrow))))
