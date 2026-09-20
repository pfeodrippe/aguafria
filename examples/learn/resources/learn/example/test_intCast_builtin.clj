(ns learn.example.test-intCast-builtin
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deftest integer-cast-panic-test
  (let [wide (ak/var 0xabcd :u16)] ; runtime-known
    (ak/= :_ (& wide))
    (let [narrow (ak/u8 (ak/intCast wide))]
      (ak/= :_ narrow))))

(comment
  ;; This deliberately panics and can terminate this JVM.
  (integer-cast-panic-test))
