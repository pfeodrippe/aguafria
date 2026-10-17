(ns aguafria.zig.prepared-rejection-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :refer :all]
            [aguafria.zig :as a]))

(a/defconst initial 1234)

(a/defn -invalid :void []
  (let [immutable-value 5678]
    (+= immutable-value 1)))

(a/defn main :void []
  (-invalid))
