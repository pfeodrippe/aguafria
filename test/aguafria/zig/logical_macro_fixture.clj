(ns aguafria.zig.logical-macro-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]
            [clojure.core :as c]))

(c/defn standalone [left right]
  (a/value (and left (or false right))))

(a/defn prepared-site :void []
  (= :_ (and true (or false true))))
