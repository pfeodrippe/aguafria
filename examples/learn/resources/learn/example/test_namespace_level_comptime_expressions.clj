(ns learn.example.test-namespace-level-comptime-expressions
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- firstNPrimes [:array n :i32]
  [[n {:attrs #{k/comptime}} :usize]]
  (let [prime-list (k/var k/undefined [:array n :i32])
        next-index (k/var 0 :usize)
        test-number (k/var 2 :i32)]
    (a/while-loop {:continue (a/assign-expr "+=" test-number 1)}
                  (k/< next-index (:len prime-list))
                  (let [test-prime-index (k/var 0 :usize)
                        is-prime (k/var true)]
                    (a/while-loop {:continue (a/assign-expr "+=" test-prime-index 1)}
                                  (k/< test-prime-index next-index)
                                  (when (k/== (k/% test-number (a/get prime-list test-prime-index)) 0)
                                    (k/= is-prime false)
                                    (k/break)))
                    (when is-prime
                      (k/= (a/get prime-list next-index) test-number)
                      (k/+= next-index 1))))
    prime-list))

(a/defn- sum :i32
  [[numbers [:slice-const :i32]]]
  (let [result (k/var 0 :i32)]
    (k/for [x numbers]
      (k/+= result x))
    result))

(a/defconst first-25-primes (firstNPrimes 25))
(a/defconst sum-of-first-25-primes (sum (k/& first-25-primes)))

(a/deftest variable-values
  (try (testing/expectEqual 1060 sum-of-first-25-primes)))

(comment
  (variable-values))
