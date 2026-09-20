(ns learn.example.test-optional-pointer
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest optional-pointer-test
  (let [^:var pointer (ak/as nil [:optional [:* :i32]])
        ^:var value (ak/i32 1)]
    (set! pointer (& value))
    (try (testing/expectEqual 1 (deref (az/unwrap pointer))))
    ;; Zero represents null, so optional pointers need no extra storage.
    (try (testing/expectEqual (ak/sizeOf (az/type [:optional [:* :i32]]))
                              (ak/sizeOf (az/type [:* :i32]))))))

(comment
  (optional-pointer-test))
