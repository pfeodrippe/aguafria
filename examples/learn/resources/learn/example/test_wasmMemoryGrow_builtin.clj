(ns learn.example.test-wasmMemoryGrow-builtin
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as k]
            [aguafria.std.Target :as target]
            [aguafria.std.Target.Cpu :as cpu]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst native-architecture
  (-> builtin/target
      target/-cpu
      cpu/-arch))

(az/deftest wasm-memory-growth-test
  (when (k/!= native-architecture :.wasm32)
    (k/return (az/error-value :SkipZigTest)))
  (let [previous-pages (k/wasmMemorySize 0)]
    (try (testing/expectEqual (k/wasmMemoryGrow 0 1) previous-pages))
    (try (testing/expectEqual (k/wasmMemorySize 0) (k/+ previous-pages 1)))))

(comment
  (wasm-memory-growth-test))
