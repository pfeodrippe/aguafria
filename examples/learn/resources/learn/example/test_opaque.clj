(ns learn.example.test-opaque
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defconst Derp (az/opaque
                   []))
(az/defconst Wat (az/opaque
                  []))

(az/defextern bar :void [[d [:* Derp]]])

(az/defn foo :void {:zig/qualifiers "callconv(.c)"} [[w [:* Wat]]]
  (bar w))

(az/deftest call-foo
  (foo k/undefined))

(comment
  (call-foo))
