(ns learn.example.testing-introduction
  (:require aguafria.std
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest add-one-expectation-test
  ;; The Standard Library contains useful functions to help create tests.
  ;; `expect` is a function that verifies its argument is true.
  ;; It returns an error when false to indicate a failure.
  ;; `try` returns that error to the test runner to report the failed test.
  (try (testing/expect (== (addOne 41) 42)))

  ;; A more specific function such as `expectEqual` is usually more convenient.
  ;; It gives clearer and more helpful error messages when a test fails.
  (try (testing/expectEqual 42 (addOne 41))))

(az/deftest add-one-doctest
  ;; The Zig version uses an identifier doctest for addOne. Here the test
  ;; is an ordinary named Aguafria Var, with the same assertion.
  (try (testing/expectEqual 42 (addOne 41))))

(az/defn- addOne :i32
  "The function `addOne` adds one to the number given as its argument."
  [[number :i32]]
  (+ number 1))
