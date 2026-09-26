(ns learn.example.testing-introduction
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- addOne :i32
  "The function `addOne` adds one to the number given as its argument."
  [[number :i32]]
  (k/+ number 1))

(az/deftest expect-addOne-adds-one-to-41
  ;; The Standard Library contains useful functions to help create tests.
  ;; `expect` is a function that verifies its argument is true.
  ;; It will return an error if its argument is false to indicate a failure.
  ;; `try` is used to return an error to the test runner to notify it that the test failed.
  (try (testing/expect (k/== (addOne 41) 42)))

  ;; However, in most cases it is more convenient to use a more specific function like `expectEqual`.
  ;; This gives you much clearer and more helpful error messages when a test fails.
  (try (testing/expectEqual 42 (addOne 41))))

(az/deftest add-one-doctest
  ;; A test name can also be written using an identifier.
  ;; This is a doctest, and serves as documentation for `addOne`.
  (try (testing/expectEqual 42 (addOne 41))))

(comment
  (expect-addOne-adds-one-to-41)
  (add-one-doctest))
