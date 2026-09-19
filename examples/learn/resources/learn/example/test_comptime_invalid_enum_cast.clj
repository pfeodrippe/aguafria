(ns learn.example.test-comptime-invalid-enum-cast
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defconst Foo
  (az/container {:kind :enum}
    (az/enum-field-decl :a)
    (az/enum-field-decl :b)
    (az/enum-field-decl :c)))

(az/defcomptime reject-invalid-tag
  (let [^{:zig/type :u2} tag-value 3
        ^{:zig/type Foo} value (ak/enumFromInt tag-value)]
    (set! _ value)))
