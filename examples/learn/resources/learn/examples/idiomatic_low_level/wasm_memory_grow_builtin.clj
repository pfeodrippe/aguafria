(ns learn.examples.idiomatic-low-level.wasm-memory-grow-builtin
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst native-architecture
  (-> (ak/import "builtin")
      (az/field :target)
      (az/field :cpu)
      (az/field :arch)))

(az/deftest wasm-memory-growth-test
  (when (!= native-architecture :.wasm32)
    (ak/return (az/error-value :SkipZigTest)))
  (let [previous-pages (ak/wasmMemorySize 0)]
    (try (testing/expectEqual (ak/wasmMemoryGrow 0 1) previous-pages))
    (try (testing/expectEqual (ak/wasmMemorySize 0) (+ previous-pages 1)))))
