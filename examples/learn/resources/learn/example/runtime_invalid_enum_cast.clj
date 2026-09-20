(ns learn.example.runtime-invalid-enum-cast
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defenum Foo
  [:a
   :b
   :c])

(az/defn main :void []
  (let [^{:var :u2} tag-value 3]
    (set! _ (ak/& tag-value))
    (let [^{:zig/type Foo} value (ak/enumFromInt tag-value)]
      (debug/print "value: {s}\n" [(ak/tagName value)]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
