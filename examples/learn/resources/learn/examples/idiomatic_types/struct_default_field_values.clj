(ns learn.examples.idiomatic-types.struct-default-field-values
  "Converted from struct_default_field_values.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defconst Foo
  (az/container {:kind :struct}
    (az/field-decl :a :i32 1234)
    (az/field-decl :b :i32)))

(az/deftest default-fields-test
  (let [^{:zig/type Foo} value {:b 5}]
    (when (!= (+ (az/field value :a) (az/field value :b)) 1239)
      (az/comptime-stmt (ak/unreachable)))))
