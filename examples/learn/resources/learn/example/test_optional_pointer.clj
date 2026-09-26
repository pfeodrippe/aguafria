(ns learn.example.test-optional-pointer
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest optional-pointers
  ;; Pointers cannot be null. If you want a null pointer, use the optional
  ;; prefix `?` to make the pointer type optional.
  (let [ptr (k/var nil [:optional [:* :i32]])
        x (k/var 1 :i32)]
    (k/= ptr (k/& x))
    (try (testing/expectEqual 1 (deref (az/unwrap ptr))))
    ;; Optional pointers are the same size as normal pointers, because pointer
    ;; value 0 is used as the null value.
    (try (testing/expectEqual (k/sizeOf (az/type [:optional [:* :i32]]))
                              (k/sizeOf (az/type [:* :i32]))))))

(comment
  (optional-pointers))
