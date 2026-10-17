(ns learn.example.values
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

;; Top-level declarations are order-independent:

;; Custom error set definition:
(a/defconst ExampleErrorSet
  (a/type [:error-set [:ExampleErrorVariant]]))

(a/defn main :void
  []
  ;; integers
  (let [one-plus-one (i32 (+ 1 1))]
    (debug/print "1 + 1 = {}\n" [one-plus-one]))

  ;; floats
  (let [seven-div-three (f32 (/ 7.0 3.0))]
    (debug/print "7.0 / 3.0 = {}\n" [seven-div-three]))

  ;; boolean
  (debug/print "{}\n{}\n{}\n" [(and true false) (or true false) (! true)])

  ;; optional
  (let [optional-value (k/var (as nil [:optional [:slice-const :u8]]))]
    (debug/assert (== optional-value nil))
    (debug/print "\noptional 1\ntype: {}\nvalue: {?s}\n"
                 [(TypeOf optional-value) optional-value])

    (= optional-value "hi")
    (debug/assert (!= optional-value nil))
    (debug/print "\noptional 2\ntype: {}\nvalue: {?s}\n"
                 [(TypeOf optional-value) optional-value]))

  ;; error union
  (let [number-or-error (-> (:ExampleErrorVariant ExampleErrorSet)
                            (as [:error-union ExampleErrorSet :i32])
                            k/var)]
    (debug/print "\nerror union 1\ntype: {}\nvalue: {!}\n"
                 [(TypeOf number-or-error) number-or-error])

    (= number-or-error 1234)
    (debug/print "\nerror union 2\ntype: {}\nvalue: {!}\n"
                 [(TypeOf number-or-error) number-or-error])))

(comment
  (main))
