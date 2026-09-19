(ns learn.examples.idiomatic-safety.runtime-wrong-union-field-access
  "Converted from runtime_wrong_union_field_access.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defconst Foo
  (az/container {:kind :union}
    (az/field-decl :float :f32)
    (az/field-decl :int :u32)))

(az/defn- overwrite-inactive-field :void [[value [:* Foo]]]
  (set! (az/field value :float) 12.34)
  (debug/print "value: {}\n" [(az/field value :float)]))

(az/defn main :void []
  (let [^:var value (az/init Foo {:int 42})]
    (overwrite-inactive-field (ak/& value))))
