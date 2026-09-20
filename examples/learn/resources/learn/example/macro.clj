(ns learn.example.macro
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn foo :c_int
  {:attrs #{:export}}
  []
  (let [^{:var :c_int} a 1]
    (set! _ (& a))
    (let [^{:var :c_int} b 2]
      (set! _ (& b))
      (+ a b))))

(az/defconst MAKELOCAL
  (ak/compileError "unable to translate C expr: unexpected token .Equal")) ; macro.c:1:9

(comment
  (foo))
