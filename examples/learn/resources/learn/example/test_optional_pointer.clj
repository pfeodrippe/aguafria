(ns learn.example.test-optional-pointer
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest optional-pointer-test
  (let [pointer (k/var nil [:optional [:* :i32]])
        value (k/var 1 :i32)]
    (k/= pointer (k/& value))
    (try (testing/expectEqual 1 (deref (az/unwrap pointer))))
    ;; Zero represents null, so optional pointers need no extra storage.
    (try (testing/expectEqual (k/sizeOf (az/type [:optional [:* :i32]]))
                              (k/sizeOf (az/type [:* :i32]))))))

(comment
  (optional-pointer-test))
