(ns learn.example.test-type-coercion
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn foo :void
  [[b :u16]]
  (= :_ b))

(a/deftest type-coercion-variable-declaration
  (let [a (u8 1)
        b (u16 a)]
    (= :_ b)))

(a/deftest type-coercion-function-call
  (let [a (u8 1)]
    (foo a)))

(a/deftest type-coercion-as-builtin
  (let [a (u8 1)
        b (as a :u16)]
    (= :_ b)))

(comment
  (type-coercion-variable-declaration)
  (type-coercion-function-call)
  (type-coercion-as-builtin))
