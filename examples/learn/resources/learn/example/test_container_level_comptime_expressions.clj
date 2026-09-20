(ns learn.example.test-container-level-comptime-expressions
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst first-25-primes (first-primes 25))
(az/defconst sum-of-first-25-primes (sum (& first-25-primes)))

(az/defn- first-primes [:array amount :i32]
  [[amount {:zig/prefix "comptime"} :usize]]
  (let [^:var prime-list (ak/as ak/undefined [:array amount :i32])
        ^:var next-index (ak/usize 0)
        ^:var candidate (ak/i32 2)]
    (az/while-loop {:continue (az/assign-expr "+=" candidate 1)}
      (< next-index (az/field prime-list :len))
      (let [^:var divisor-index (ak/usize 0)
            ^:var prime? true]
        (az/while-loop {:continue (az/assign-expr "+=" divisor-index 1)}
          (< divisor-index next-index)
          (when (== (ak/% candidate (az/index prime-list divisor-index)) 0)
            (set! prime? false)
            (ak/break)))
        (when prime?
          (set! (az/index prime-list next-index) candidate)
          (ak/+= next-index 1))))
    prime-list))

(az/defn- sum :i32
  [[numbers [:slice-const :i32]]]
  (let [^:var result (ak/i32 0)]
    (for [number numbers]
      (ak/+= result number))
    result))

(az/deftest compile-time-variable-values-test
  (try (testing/expectEqual 1060 sum-of-first-25-primes)))

(comment
  (compile-time-variable-values-test))
