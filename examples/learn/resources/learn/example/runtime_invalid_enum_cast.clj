(ns learn.example.runtime-invalid-enum-cast
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defenum Foo
  [:a
   :b
   :c])

(az/defn main :void []
  (let [tag-value (k/var 3 :u2)]
    (k/= :_ (k/& tag-value))
    (let [value (k/as (k/enumFromInt tag-value) Foo)]
      (debug/print "value: {s}\n" [(k/tagName value)]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
