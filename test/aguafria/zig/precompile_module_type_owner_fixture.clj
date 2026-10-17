(ns aguafria.zig.precompile-module-type-owner-fixture
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.zig.precompile-module-type-exports-fixture :as exports]
            [aguafria.keyword :as k :refer :all]))

(a/defn selected-value :u32 []
  (let [instance (a/init {:value 7} (a/field exports "Container"))]
    ((:read-value instance))))
