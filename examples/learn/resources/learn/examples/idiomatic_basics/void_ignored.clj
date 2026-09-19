(ns learn.examples.idiomatic-basics.void-ignored
  "Converted from test_void_ignored.zig"
  (:require [aguafria.zig :as az]))

(az/deftest void-ignored-test
  (returns-void))

(az/deftest explicit-discard-test
  (set! _ (returns-integer)))

(az/defn- returns-void :void [])

(az/defn- returns-integer :i32
  []
  1234)
