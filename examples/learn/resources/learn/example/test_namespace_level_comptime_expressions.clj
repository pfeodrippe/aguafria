(ns learn.example.test-namespace-level-comptime-expressions
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- firstNPrimes [:array n :i32]
  [[n {:attrs #{comptime}} :usize]]
  (let [prime-list (k/var undefined [:array n :i32])
        next-index (k/var 0 :usize)
        test-number (k/var 2 :i32)]
    (a/while-loop {:continue (a/assign-expr "+=" test-number 1)}
                  (< next-index (:len prime-list))
                  (let [test-prime-index (k/var 0 :usize)
                        is-prime (k/var true)]
                    (a/while-loop {:continue (a/assign-expr "+=" test-prime-index 1)}
                                  (< test-prime-index next-index)
                                  (when (== (% test-number (a/get prime-list test-prime-index)) 0)
                                    (= is-prime false)
                                    (break)))
                    (when is-prime
                      (= (a/get prime-list next-index) test-number)
                      (+= next-index 1))))
    prime-list))

(a/defn- sum :i32
  [[numbers [:slice-const :i32]]]
  (let [result (k/var 0 :i32)]
    (for [x numbers]
      (+= result x))
    result))

(a/defconst first-25-primes (firstNPrimes 25))
(a/defconst sum-of-first-25-primes (sum (k/& first-25-primes)))

(a/deftest variable-values
  (k/try (testing/expectEqual 1060 sum-of-first-25-primes)))

(comment
  (variable-values))
