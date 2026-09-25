(ns learn.example.macro
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn foo :c_int
  {:attrs #{k/export}}
  []
  (let [a (k/var 1 :c_int)]
    (k/= :_ (k/& a))
    (let [b (k/var 2 :c_int)]
      (k/= :_ (k/& b))
      (k/+ a b))))

(az/defconst MAKELOCAL
  (k/compileError "unable to translate C expr: unexpected token .Equal")) ; macro.c:1:9

(comment
  (foo))
