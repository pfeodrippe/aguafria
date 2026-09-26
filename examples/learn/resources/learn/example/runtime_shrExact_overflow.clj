(ns learn.example.runtime-shrExact-overflow
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defimport target "builtin"
  [[native-arch "cpu.arch"] [zig-backend "zig_backend"]])

(az/defn main :void []
  (let [x (k/var 2r10101010 :u8)] ; runtime-known
    (k/= :_ (k/& x))
    (let [y (k/shrExact x 2)]
      (debug/print "value: {}\n" [y]))
    (when (and (or ((:isPowerPC target/native-arch))
                   ((:isRISCV target/native-arch))
                   ((:isLoongArch target/native-arch))
                   (k/== target/native-arch :.s390x))
               (k/== target/zig-backend :.stage2_llvm))
      (k/panic "https://github.com/ziglang/zig/issues/24304"))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
