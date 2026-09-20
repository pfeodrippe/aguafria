(ns fixture.test-before-helper
  (:require [aguafria.zig :as az]))

(az/deftest cannot-see-later-helper
  (later-helper))

(az/defn- later-helper :void [])
