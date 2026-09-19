(ns learn.examples.idiomatic-types.undefined-active-union-field
  "Converted from undefined_active_union_field.zig"
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defconst Value
  (az/container {:kind :union}
    (az/field-decl :float :f32)
    (az/field-decl :int :u32)))

(az/defn initialize-float :void [[value [:* Value]]]
  (set! (az/field value :float) 12.34))

(az/defn main :void []
  (let [^:var value (az/init Value {:int 42})]
    ;; Select the float field first; its payload can be initialized separately.
    (set! value (az/init Value {:float ak/undefined}))
    (initialize-float (& value))
    (debug/print "value: {}\n" [(az/field value :float)])))
