(ns learn.example.test-container-level-comptime-expressions
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- firstNPrimes [:array amount :i32]
  [[amount {:attrs #{k/comptime}} :usize]]
  (let [prime-list (k/var k/undefined [:array amount :i32])
        next-index (k/var 0 :usize)
        candidate (k/var 2 :i32)]
    (az/while-loop {:continue (az/assign-expr "+=" candidate 1)}
      (k/< next-index (:len prime-list))
      (let [divisor-index (k/var 0 :usize)
            prime? (k/var true)]
        (az/while-loop {:continue (az/assign-expr "+=" divisor-index 1)}
          (k/< divisor-index next-index)
          (when (k/== (k/% candidate (az/get prime-list divisor-index)) 0)
            (k/= prime? false)
            (k/break)))
        (when prime?
          (k/= (az/get prime-list next-index) candidate)
          (k/+= next-index 1))))
    prime-list))

(az/defn- sum :i32
  [[numbers [:slice-const :i32]]]
  (let [result (k/var 0 :i32)]
    (k/for [number numbers]
      (k/+= result number))
    result))

(az/defconst first-25-primes (firstNPrimes 25))
(az/defconst sum-of-first-25-primes (sum (k/& first-25-primes)))

(az/deftest compile-time-variable-values-test
  (try (testing/expectEqual 1060 sum-of-first-25-primes)))

(comment
  (compile-time-variable-values-test))
