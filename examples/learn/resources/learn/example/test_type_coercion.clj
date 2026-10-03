(ns learn.example.test-type-coercion
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn foo :void
  [[b :u16]]
  (k/= :_ b))

(a/deftest type-coercion-variable-declaration
  (let [a (k/u8 1)
        b (k/u16 a)]
    (k/= :_ b)))

(a/deftest type-coercion-function-call
  (let [a (k/u8 1)]
    (foo a)))

(a/deftest type-coercion-as-builtin
  (let [a (k/u8 1)
        b (k/as a :u16)]
    (k/= :_ b)))

(comment
  (type-coercion-variable-declaration)
  (type-coercion-function-call)
  (type-coercion-as-builtin))
