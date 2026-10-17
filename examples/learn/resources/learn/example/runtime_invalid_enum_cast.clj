(ns learn.example.runtime-invalid-enum-cast
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defenum Foo {:type :u2}
  [:a
   :b
   :c])

(a/defn- foo :void [[a :u2]]
  (let [b (as (fromBackingInt a) Foo)]
    (debug/print "value: {s}\n" [(tagName b)])))

(a/defn main :void []
  (foo 3))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
