(ns learn.examples.idiomatic-pointers.optional-pointer
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest optional-pointer-test
  (let [^{:var [:optional [:* :i32]]} pointer nil
        ^{:var :i32} value 1]
    (set! pointer (& value))
    (try (testing/expectEqual 1 (deref (az/unwrap pointer))))
    ;; Zero represents null, so optional pointers need no extra storage.
    (try (testing/expectEqual (ak/sizeOf (az/type [:optional [:* :i32]]))
                              (ak/sizeOf (az/type [:* :i32]))))))
