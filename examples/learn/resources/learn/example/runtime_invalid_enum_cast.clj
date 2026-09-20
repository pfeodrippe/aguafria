(ns learn.example.runtime-invalid-enum-cast
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defenum Foo
  [:a
   :b
   :c])

(az/defn main :void []
  (let [tag-value (ak/var 3 :u2)]
    (ak/= :_ (ak/& tag-value))
    (let [value (ak/as (ak/enumFromInt tag-value) Foo)]
      (debug/print "value: {s}\n" [(ak/tagName value)]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
