(ns learn.example.test-wasmMemoryGrow-builtin
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as k]
            [aguafria.std.Target :as target]
            [aguafria.std.Target.Cpu :as cpu]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst native-arch
  (-> builtin/target
      target/-cpu
      cpu/-arch))

(az/deftest wasmMemoryGrow
  (when (k/!= native-arch :.wasm32)
    (k/return (az/error-value :SkipZigTest)))
  (let [prev (k/wasmMemorySize 0)]
    (try (testing/expectEqual (k/wasmMemoryGrow 0 1) prev))
    (try (testing/expectEqual (k/wasmMemorySize 0) (k/+ prev 1)))))

(comment
  (wasmMemoryGrow))
