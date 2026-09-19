(ns learn.example.change-active-union-field
  (:require aguafria.std
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defconst Value
  (az/container {:kind :union}
    (az/field-decl :float :f32)
    (az/field-decl :int :u32)))

(az/defn change-to-float :void [[value [:* Value]]]
  (set! @value (az/init Value {:float 12.34}))
  (debug/print "value: {}\n" [(az/field value :float)]))

(az/defn main :void []
  (let [^:var value (az/init Value {:int 42})]
    (change-to-float (& value))))
