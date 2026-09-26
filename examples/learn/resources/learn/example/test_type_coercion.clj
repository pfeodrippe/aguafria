(ns learn.example.test-type-coercion
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn foo :void
  [[b :u16]]
  (k/= :_ b))

(az/deftest type-coercion-variable-declaration
  (let [a (k/u8 1)
        b (k/u16 a)]
    (k/= :_ b)))

(az/deftest type-coercion-function-call
  (let [a (k/u8 1)]
    (foo a)))

(az/deftest type-coercion-as-builtin
  (let [a (k/u8 1)
        b (k/as a (az/type :u16))]
    (k/= :_ b)))

(comment
  (type-coercion-variable-declaration)
  (type-coercion-function-call)
  (type-coercion-as-builtin))
