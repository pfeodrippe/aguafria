(ns learn.example.test-comptime-unwrap-null
  (:require [aguafria.zig :as az]))

(az/defcomptime reject-absent-number
  (let [^{:zig/type [:optional :i32]} optional-number nil
        number (az/unwrap optional-number)]
    (set! _ number)))

(comment
  ;; Evaluate the comptime declaration above; it runs during native compilation, not at runtime.
  )
