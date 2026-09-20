(ns learn.example.redundant-fqn
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defconst json {:attrs #{:public}}
  (az/container {:kind :struct}
    (az/const-decl JsonValue {:attrs #{:public}}
      (az/container {:kind :union :enum? true}
        (az/field-decl :number :f64)
        (az/field-decl :boolean :bool)))))

(az/defn main :void []
  (debug/print "{s}\n" [(ak/typeName (az/field json :JsonValue))]))

(comment
  (main))
