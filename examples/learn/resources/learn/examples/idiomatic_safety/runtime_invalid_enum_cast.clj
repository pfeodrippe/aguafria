(ns learn.examples.idiomatic-safety.runtime-invalid-enum-cast
  "Converted from runtime_invalid_enum_cast.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defconst Foo
  (az/container {:kind :enum}
    (az/enum-field-decl :a)
    (az/enum-field-decl :b)
    (az/enum-field-decl :c)))

(az/defn main :void []
  (let [^{:var :u2} tag-value 3]
    (set! _ (ak/& tag-value))
    (let [^{:zig/type Foo} value (ak/enumFromInt tag-value)]
      (debug/print "value: {s}\n" [(ak/tagName value)]))))
