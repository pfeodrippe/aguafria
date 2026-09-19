(ns learn.examples.idiomatic-basics.void-ignored
  (:require [aguafria.zig :as az]))

(az/deftest void-ignored-test
  (returns-void))

(az/deftest explicit-discard-test
  (set! _ (returns-integer)))

(az/defn- returns-void :void [])

(az/defn- returns-integer :i32
  []
  1234)
