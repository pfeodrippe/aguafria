(ns learn.example.test-no-op-casts
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn- foo :void
  [[_ [:*const :i32]]])

(az/deftest const-qualification-test
  (let [value (k/var 1 :i32)
        pointer (k/as (k/& value) [:* :i32])]
    (foo pointer)))

(comment
  (const-qualification-test))
