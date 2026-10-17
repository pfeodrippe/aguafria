(ns aguafria.zig.extern-load-prepared-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :refer :all]
            [aguafria.zig :as a]))

(a/defextern unused :void [])

;; Preparation and source loading must validate, but never invoke, this body.
(a/defn main :void []
  (panic "namespace validation executed an application body"))
