(ns learn.example.test-no-op-casts
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deftest const-qualification-test
  (let [^:var value (ak/i32 1)
        pointer (ak/as (& value) [:* :i32])]
    (accept-const-pointer pointer)))

(az/defn- accept-const-pointer :void
  [[_ [:*const :i32]]])

(comment
  (const-qualification-test))
