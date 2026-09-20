(ns learn.example.macro
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn foo :c_int
  {:attrs #{:export}}
  []
  (let [^:var a (ak/as 1 :c_int)]
    (set! _ (& a))
    (let [^:var b (ak/as 2 :c_int)]
      (set! _ (& b))
      (+ a b))))

(az/defconst MAKELOCAL
  (ak/compileError "unable to translate C expr: unexpected token .Equal")) ; macro.c:1:9

(comment
  (foo))
