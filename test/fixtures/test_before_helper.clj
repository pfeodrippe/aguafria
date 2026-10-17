(ns fixture.test-before-helper
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/deftest cannot-see-later-helper
  (later-helper))

(a/defn- later-helper :void [])
