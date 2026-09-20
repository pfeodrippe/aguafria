(ns learn.example.test-comptime-invalid-null-pointer-cast
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defcomptime reject-null-pointer
  (let [^{:zig/type [:optional [:* :i32]]} optional-pointer nil
        ;; Intentionally invalid: a nonoptional pointer cannot hold null.
        ^{:zig/type [:* :i32]} pointer (ak/ptrCast optional-pointer)]
    (set! _ pointer)))

(comment
  ;; Evaluate the comptime declaration above; it runs during native compilation, not at runtime.
  )
