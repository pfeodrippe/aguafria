(ns learn.example.test-opaque
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defconst Derp (a/opaque
                  []))
(a/defconst Wat (a/opaque
                 []))

(a/defextern bar :void [[d [:* Derp]]])

(a/defn foo :void {:zig/qualifiers "callconv(.c)"} [[w [:* Wat]]]
  (bar w))

(a/deftest call-foo
  (foo undefined))

(comment
  (call-foo))
