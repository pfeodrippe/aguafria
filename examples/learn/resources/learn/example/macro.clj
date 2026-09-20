(ns learn.example.macro
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn foo :c_int
  {:attrs #{:export}}
  []
  (let [a (ak/var 1 :c_int)]
    (ak/= :_ (& a))
    (let [b (ak/var 2 :c_int)]
      (ak/= :_ (& b))
      (+ a b))))

(az/defconst MAKELOCAL
  (ak/compileError "unable to translate C expr: unexpected token .Equal")) ; macro.c:1:9

(comment
  (foo))
