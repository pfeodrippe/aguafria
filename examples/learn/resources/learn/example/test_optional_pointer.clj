(ns learn.example.test-optional-pointer
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest optional-pointers
  ;; Pointers cannot be null. If you want a null pointer, use the optional
  ;; prefix `?` to make the pointer type optional.
  (let [ptr (k/var nil [:optional [:* :i32]])
        x (k/var 1 :i32)]
    (= ptr (k/& x))
    (k/try (testing/expectEqual 1 (a/deref (a/unwrap ptr))))
    ;; Optional pointers are the same size as normal pointers, because pointer
    ;; value 0 is used as the null value.
    (k/try (testing/expectEqual (sizeOf [:optional [:* :i32]])
                                (sizeOf [:* :i32])))))

(comment
  (optional-pointers))
