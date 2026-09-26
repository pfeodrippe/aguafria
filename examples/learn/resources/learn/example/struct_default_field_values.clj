(ns learn.example.struct-default-field-values
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defstruct Foo
  [[:a {:default 1234} :i32]
   [:b :i32]])

(az/deftest default-fields-test
  (let [value (Foo {:b 5})]
    (when (k/!= (k/+ (:a value) (:b value)) 1239)
      (az/comptime-stmt (k/unreachable)))))

(comment
  (default-fields-test))
