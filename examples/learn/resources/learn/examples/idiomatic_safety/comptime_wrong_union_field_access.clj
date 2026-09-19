(ns learn.examples.idiomatic-safety.comptime-wrong-union-field-access
  "Converted from test_comptime_wrong_union_field_access.zig"
  (:require [aguafria.zig :as az]))

(az/defconst Foo
  (az/container {:kind :union}
    (az/field-decl :float :f32)
    (az/field-decl :int :u32)))

(az/defcomptime reject-inactive-field
  (let [^:var value (az/init Foo {:int 42})]
    (set! (az/field value :float) 12.34)))
