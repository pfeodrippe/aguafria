(ns learn.example.struct-default-field-values
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defstruct Foo
  [[:a {:default 1234} :i32]
   [:b :i32]])

(az/deftest default-fields-test
  (let [value (Foo {:b 5})]
    (when (!= (+ (az/field value :a) (az/field value :b)) 1239)
      (az/comptime-stmt (ak/unreachable)))))

(comment
  (default-fields-test))
