(ns learn.example.values
  (:require [aguafria.keyword :as k]
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
  (let [one-plus-one (k/i32 (k/+ 1 1))]
    (debug/print "1 + 1 = {}\n" [one-plus-one]))

  ;; Floats.
  (let [seven-div-three (k/f32 (k// 7.0 3.0))]
    (debug/print "7.0 / 3.0 = {}\n" [seven-div-three]))

  ;; Boolean.
  (debug/print "{}\n{}\n{}\n" [(and true false) (or true false) (k/! true)])

  ;; Optional.
  (let [optional-value (k/var (k/as nil [:optional [:slice-const :u8]]))]
    (debug/assert (k/== optional-value nil))
    (debug/print "\noptional 1\ntype: {}\nvalue: {?s}\n"
                 [(k/TypeOf optional-value) optional-value])

    (k/= optional-value "hi")
    (debug/assert (k/!= optional-value nil))
    (debug/print "\noptional 2\ntype: {}\nvalue: {?s}\n"
                 [(k/TypeOf optional-value) optional-value]))

  ;; Error union.
  (let [number-or-error (-> (az/field ExampleErrorSet :ExampleErrorVariant)
                            (k/as [:error-union ExampleErrorSet :i32])
                            k/var)]
    (debug/print "\nerror union 1\ntype: {}\nvalue: {!}\n"
                 [(k/TypeOf number-or-error) number-or-error])

    (k/= number-or-error 1234)
    (debug/print "\nerror union 2\ntype: {}\nvalue: {!}\n"
                 [(k/TypeOf number-or-error) number-or-error])))

(comment
  (main))
