(ns learn.example.test-global-assembly
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defcomptime install-addition
  (asm
   (a/multiline-string
    [".global my_func;"
     ".type my_func, @function;"
     "my_func:"
     "  lea (%rdi,%rsi,1),%eax"
     "  retq"])))

(a/defextern my-func :i32
  [[a :i32] [b :i32]])

(a/deftest global-assembly
  (k/try (testing/expectEqual 46 (my-func 12 34))))

(comment
  (global-assembly))
