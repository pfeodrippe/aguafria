(ns learn.example.test-wasmMemoryGrow-builtin
  (:refer-clojure :only [])
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as k :refer :all]
            [aguafria.std.Target :as target]
            [aguafria.std.Target.Cpu :as cpu]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]
            [clojure.core :as c]))

(a/defconst native-arch
  (-> builtin/target
      target/-cpu
      cpu/-arch))

(a/deftest wasmMemoryGrow
  (when (!= native-arch :wasm32)
    (return (a/error-value :SkipZigTest)))
  (let [prev (wasmMemorySize 0)]
    ;; Zig 0.17 returns isize from grow (it can return -1), but usize from
    ;; size. The upstream test's direct expectEqual has incompatible types
    ;; when actually compiled for wasm32 rather than skipped on the host.
    (k/try (testing/expectEqual (as (intCast prev) :isize)
                                (k/wasmMemoryGrow 0 1)))
    (k/try (testing/expectEqual (+ prev 1) (wasmMemorySize 0)))))

(comment
  ;; A native-host test would skip this case. Build a real wasm32 WASI test
  ;; executable and run it in Node so both memory-growth assertions execute.
  (c/require '[clojure.java.io :as io]
             '[clojure.java.shell :as sh])
  (let [source "build/test_wasmMemoryGrow_builtin.zig"
        wasm "build/test_wasmMemoryGrow_builtin.wasm"]
    (io/make-parents source)
    (c/spit source (a/source 'learn.example.test-wasmMemoryGrow-builtin))
    (let [built (sh/sh (a/zig-executable) "test" source
                       "-target" "wasm32-wasi" "--test-no-exec"
                       (c/str "-femit-bin=" wasm))]
      (when-not (c/zero? (:exit built))
        (throw (c/ex-info "WebAssembly test compilation failed" built))))
    (let [result (sh/sh "node" "--no-warnings" "-e"
                        (c/slurp (io/resource "learn/host/wasi.cjs"))
                        wasm)]
      (when-not (c/zero? (:exit result))
        (throw (c/ex-info "WebAssembly test failed" result)))
      (clojure.core/print (c/str (:out result) (:err result))))))
