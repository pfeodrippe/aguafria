(ns simple-game.web-bindings
  "Load generated WebGL bindings without introducing a native bridge."
  (:require [aguafria.c :as ac]
            [simple-game.generate :as generate]))

(defonce loaded? (atom false))

(defn ensure-loaded!
  []
  (when-not @loaded?
    (let [specs (generate/web-binding-specs)
          missing (remove #(.isFile ^java.io.File (:output %)) specs)]
      (when (seq missing)
        (generate/generate-web!))
      (doseq [spec specs]
        (ac/load-bindings! (:output spec)))
      (reset! loaded? true)))
  {:loaded? @loaded?
   :webgl-bindings
   (when @loaded? (ac/namespace-info 'simple-game.bindings.webgl))
   :emscripten-bindings
   (when @loaded? (ac/namespace-info 'simple-game.bindings.emscripten))})

(ensure-loaded!)
