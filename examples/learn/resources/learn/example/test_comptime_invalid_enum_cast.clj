(ns learn.example.test-comptime-invalid-enum-cast
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defenum Foo
  [:a
   :b
   :c])

(az/defcomptime reject-invalid-tag
  (let [^{:zig/type :u2} tag-value 3
        ^{:zig/type Foo} value (ak/enumFromInt tag-value)]
    (set! _ value)))
