(ns learn.examples.idiomatic-interop.enum-export-error
  "Converted from enum_export_error.zig"
  (:require [aguafria.zig :as az]))

(az/defconst Foo
  (az/container {:kind :enum}
    (az/enum-field-decl :a)
    (az/enum-field-decl :b)
    (az/enum-field-decl :c)))

(az/defn entry :void
  {:attrs #{:export}}
  [[foo Foo]]
  (set! _ foo))
