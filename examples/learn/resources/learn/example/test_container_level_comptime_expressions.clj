(ns learn.example.test-container-level-comptime-expressions
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst first-25-primes (firstNPrimes 25))
(az/defconst sum-of-first-25-primes (sum (& first-25-primes)))

(az/defn- firstNPrimes [:array amount :i32]
  [[amount {:zig/prefix "comptime"} :usize]]
  (let [prime-list (ak/var ak/undefined [:array amount :i32])
        next-index (ak/var 0 :usize)
        candidate (ak/var 2 :i32)]
    (az/while-loop {:continue (az/assign-expr "+=" candidate 1)}
      (< next-index (az/field prime-list :len))
      (let [divisor-index (ak/var 0 :usize)
            prime? (ak/var true)]
        (az/while-loop {:continue (az/assign-expr "+=" divisor-index 1)}
          (< divisor-index next-index)
          (when (== (ak/% candidate (az/index prime-list divisor-index)) 0)
            (ak/= prime? false)
            (ak/break)))
        (when prime?
          (ak/= (az/index prime-list next-index) candidate)
          (ak/+= next-index 1))))
    prime-list))

(az/defn- sum :i32
  [[numbers [:slice-const :i32]]]
  (let [result (ak/var 0 :i32)]
    (for [number numbers]
      (ak/+= result number))
    result))

(az/deftest compile-time-variable-values-test
  (try (testing/expectEqual 1060 sum-of-first-25-primes)))

(comment
  (compile-time-variable-values-test))
