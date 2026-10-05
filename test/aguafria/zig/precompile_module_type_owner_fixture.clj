(ns aguafria.zig.precompile-module-type-owner-fixture
  (:require [aguafria.zig :as a]
            [aguafria.zig.precompile-module-type-exports-fixture :as exports]))

(a/defn selected-value :u32 []
  (let [instance (a/init {:value 7} (a/field exports "Container"))]
    ((:read-value instance))))
