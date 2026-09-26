(ns learn.example.test-global-assembly
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defcomptime install-addition
  (k/asm
   (az/multiline-string
    [".global my_func;"
     ".type my_func, @function;"
     "my_func:"
     "  lea (%rdi,%rsi,1),%eax"
     "  retq"])))

(az/defextern my-func :i32
  [[a :i32] [b :i32]])

(az/deftest global-assembly
  (try (testing/expectEqual 46 (my-func 12 34))))

(comment
  (global-assembly))
