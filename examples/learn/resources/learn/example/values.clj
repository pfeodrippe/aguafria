(ns learn.example.values
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

;; Top-level Zig declarations are order-independent.
;; In Clojure, library names are introduced by the namespace's requires.

;; Custom error set definition.
(az/defconst ExampleErrorSet
  (az/type [:error-set [:ExampleErrorVariant]]))

(az/defn main :void
  []
  ;; Integers.
  (let [one-plus-one (ak/i32 (+ 1 1))]
    (debug/print "1 + 1 = {}\n" [one-plus-one]))

  ;; Floats.
  (let [seven-div-three (ak/f32 (/ 7.0 3.0))]
    (debug/print "7.0 / 3.0 = {}\n" [seven-div-three]))

  ;; Boolean.
  (debug/print "{}\n{}\n{}\n" [(and true false) (or true false) (ak/! true)])

  ;; Optional.
  (let [^:var optional-value (ak/as nil [:optional [:slice-const :u8]])]
    (debug/assert (== optional-value nil))
    (debug/print "\noptional 1\ntype: {}\nvalue: {?s}\n"
                 [(ak/TypeOf optional-value) optional-value])

    (set! optional-value "hi")
    (debug/assert (!= optional-value nil))
    (debug/print "\noptional 2\ntype: {}\nvalue: {?s}\n"
                 [(ak/TypeOf optional-value) optional-value]))

  ;; Error union.
  (let [^:var number-or-error
        (ak/as (az/field ExampleErrorSet :ExampleErrorVariant)
               [:error-union ExampleErrorSet :i32])]
    (debug/print "\nerror union 1\ntype: {}\nvalue: {!}\n"
                 [(ak/TypeOf number-or-error) number-or-error])

    (set! number-or-error 1234)
    (debug/print "\nerror union 2\ntype: {}\nvalue: {!}\n"
                 [(ak/TypeOf number-or-error) number-or-error])))

(comment
  (main))
