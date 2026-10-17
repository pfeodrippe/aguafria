(ns learn.example.runtime-shrExact-overflow
  (:refer-clojure :only [])
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn main :void []
  (let [x (k/var 2r10101010 :u8)] ; runtime-known
    (= :_ (k/& x))
    (let [y (shrExact x 2)]
      (debug/print "value: {}\n" [y]))
    (when (and (or ((:isPowerPC (:arch builtin/cpu)))
                   ((:isRISCV (:arch builtin/cpu)))
                   ((:isLoongArch (:arch builtin/cpu)))
                   (== (:arch builtin/cpu) :s390x))
               (== builtin/zig_backend :stage2_llvm))
      (panic "https://github.com/ziglang/zig/issues/24304"))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
