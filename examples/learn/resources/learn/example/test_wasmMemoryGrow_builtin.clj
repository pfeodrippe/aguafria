(ns learn.example.test-wasmMemoryGrow-builtin
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as k]
            [aguafria.std.Target :as target]
            [aguafria.std.Target.Cpu :as cpu]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defconst native-arch
  (-> builtin/target
      target/-cpu
      cpu/-arch))

(a/deftest wasmMemoryGrow
  (when (k/!= native-arch :.wasm32)
    (k/return (a/error-value :SkipZigTest)))
  (let [prev (k/wasmMemorySize 0)]
    ;; Zig 0.17 returns isize from grow (it can return -1), but usize from
    ;; size. The upstream test's direct expectEqual has incompatible types
    ;; when actually compiled for wasm32 rather than skipped on the host.
    (k/try (testing/expectEqual (k/as (k/intCast prev) :isize)
                                (k/wasmMemoryGrow 0 1)))
    (k/try (testing/expectEqual (k/+ prev 1) (k/wasmMemorySize 0)))))

(comment
  ;; A native-host test would skip this case. Build a real wasm32 WASI test
  ;; executable and run it in Node so both memory-growth assertions execute.
  (require '[clojure.java.io :as io]
           '[clojure.java.shell :as sh])
  (let [source "build/test_wasmMemoryGrow_builtin.zig"
        wasm "build/test_wasmMemoryGrow_builtin.wasm"]
    (io/make-parents source)
    (spit source (a/source 'learn.example.test-wasmMemoryGrow-builtin))
    (let [built (sh/sh (a/zig-executable) "test" source
                       "-target" "wasm32-wasi" "--test-no-exec"
                       (str "-femit-bin=" wasm))]
      (when-not (zero? (:exit built))
        (throw (ex-info "WebAssembly test compilation failed" built))))
    (let [result (sh/sh "node" "--no-warnings" "-e"
                        (slurp (io/resource "learn/host/wasi.cjs"))
                        wasm)]
      (when-not (zero? (:exit result))
        (throw (ex-info "WebAssembly test failed" result)))
      (clojure.core/print (str (:out result) (:err result))))))
