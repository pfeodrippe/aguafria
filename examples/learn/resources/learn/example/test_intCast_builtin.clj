(ns learn.example.test-intCast-builtin
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deftest integer-cast-panic-test
  (let [^:var wide (ak/u16 0xabcd)] ; runtime-known
    (set! _ (& wide))
    (let [narrow (ak/u8 (ak/intCast wide))]
      (set! _ narrow))))

(comment
  ;; This deliberately panics and can terminate this JVM.
  (integer-cast-panic-test))
