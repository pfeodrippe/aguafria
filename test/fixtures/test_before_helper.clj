(ns fixture.test-before-helper
  (:require [aguafria.zig :as a]))

(a/deftest cannot-see-later-helper
  (later-helper))

(a/defn- later-helper :void [])
