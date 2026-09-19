(ns learn.example.runtime-invalid-null-pointer-cast
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [^{:var [:optional [:* :i32]]} optional-pointer nil]
    (set! _ (& optional-pointer))
    ;; The same invalid conversion is diagnosed by a runtime safety check.
    (let [^{:zig/type [:* :i32]} pointer (ak/ptrCast optional-pointer)]
      (set! _ pointer))))
