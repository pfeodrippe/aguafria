(ns learn.example.test-intCast-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest integer-cast-panic-test
  (let [wide (k/var 0xabcd :u16)] ; runtime-known
    (k/= :_ (k/& wide))
    (let [narrow (k/u8 (k/intCast wide))]
      (k/= :_ narrow))))

(comment
  ;; This deliberately panics and can terminate this JVM.
  (integer-cast-panic-test))
