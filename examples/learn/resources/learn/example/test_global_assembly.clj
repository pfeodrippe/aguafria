(ns learn.example.test-global-assembly
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

;; x86-64 Linux, LLVM backend. The assembler supplies this external symbol.
(az/defcomptime install-addition
  (ak/asm
    (az/multiline-string
      [".global my_func;"
       ".type my_func, @function;"
       "my_func:"
       "  lea (%rdi,%rsi,1),%eax"
       "  retq"])))

(az/defextern my-func {:zig/prefix "extern"} :- :i32
  [[first-value :i32] [second-value :i32]])

(az/deftest global-assembly-test
  (try (testing/expectEqual 46 (my-func 12 34))))

(comment
  (global-assembly-test))
