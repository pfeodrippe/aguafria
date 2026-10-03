(ns learn.example.test-opaque
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defconst Derp (a/opaque
                  []))
(a/defconst Wat (a/opaque
                 []))

(a/defextern bar :void [[d [:* Derp]]])

(a/defn foo :void {:zig/qualifiers "callconv(.c)"} [[w [:* Wat]]]
  (bar w))

(a/deftest call-foo
  (foo k/undefined))

(comment
  (call-foo))
