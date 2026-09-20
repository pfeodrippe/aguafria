(ns learn.example.test-no-op-casts
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deftest const-qualification-test
  (let [value (ak/var 1 :i32)
        pointer (ak/as (& value) [:* :i32])]
    (foo pointer)))

(az/defn- foo :void
  [[_ [:*const :i32]]])

(comment
  (const-qualification-test))
