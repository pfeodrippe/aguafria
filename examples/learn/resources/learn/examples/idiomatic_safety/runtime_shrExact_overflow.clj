(ns learn.examples.idiomatic-safety.runtime-shrExact-overflow
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defimport target "builtin"
  [[native-arch "cpu.arch"] [zig-backend "zig_backend"]])

(az/defn main :void []
  (let [^{:var :u8} alternating-bits 2r10101010]
    (set! _ (ak/& alternating-bits))
    (let [shifted-bits (ak/shrExact alternating-bits 2)]
      (debug/print "value: {}\n" [shifted-bits]))
    (when (and (or ((az/field target/native-arch :isPowerPC))
                   ((az/field target/native-arch :isRISCV))
                   ((az/field target/native-arch :isLoongArch))
                   (== target/native-arch :.s390x))
               (== target/zig-backend :.stage2_llvm))
      (ak/panic "https://github.com/ziglang/zig/issues/24304"))))
