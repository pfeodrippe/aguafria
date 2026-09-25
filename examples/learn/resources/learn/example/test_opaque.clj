(ns learn.example.test-opaque
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defconst Derp (az/opaque
                    []))
(az/defconst Wat (az/opaque
                   []))

(az/defextern bar :void [[pointer [:* Derp]]])

(az/defn foo :void {:zig/qualifiers "callconv(.c)"} [[pointer [:* Wat]]]
  ;; Distinct opaque types remain incompatible even behind pointers.
  (bar pointer))

(az/deftest call-foo-test
  (foo ak/undefined))

(comment
  (call-foo-test))
