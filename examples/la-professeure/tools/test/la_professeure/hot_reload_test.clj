(ns la-professeure.hot-reload-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [clojure.walk :as walk]
            [la-professeure.scene :as scene]
            [la-professeure.tools.studio :as studio])
  (:import [java.io PushbackReader]))

(defn- definition [resource name]
  (with-open [reader (PushbackReader. (io/reader (io/resource resource)))]
    (loop []
      (let [form (read {:eof nil} reader)]
        (cond
          (nil? form) (throw (ex-info "Native definition was not found" {:name name}))
          (and (seq? form) (= name (second form))) form
          :else (recur))))))

(defn- publish! [namespace form]
  (binding [*ns* (the-ns namespace)] (eval form))
  (a/await! namespace))

(deftest scene-reload-preserves-live-native-state
  (when (or (some? (a/value scene/window)) (some? (a/value scene/world)))
    (throw (ex-info "Run this check in its own nREPL, not a running game" {})))
  (let [original (definition "la_professeure/scene.clj" 'animation-frame)
        saved-time (a/value scene/elapsed)
        saved-frames (a/value scene/rendered-frames)]
    (try
      (scene/ensure-world!)
      (k/= scene/elapsed 1.625)
      (k/= scene/rendered-frames 37)
      (let [world (a/value (k/intFromPtr scene/world))
            address (a/value (k/intFromPtr (k/& scene/elapsed)))]
        (is (pos? world))
        (is (= 5 (:frame (a/value (scene/snapshot)))))
        (doseq [[length expected] [[16.0 13] [4.0 1]]]
          (publish! 'la-professeure.scene (walk/postwalk-replace {8.0 length} original))
          (let [snapshot (a/value (scene/snapshot))]
            (is (= expected (:frame snapshot)))
            (is (= 1.625 (:time snapshot)))
            (is (= 37 (:rendered_frames snapshot)))
            (is (= world (a/value (k/intFromPtr scene/world))))
            (is (= address (a/value (k/intFromPtr (k/& scene/elapsed)))))))
        (publish! 'la-professeure.scene original)
        (is (= 5 (:frame (a/value (scene/snapshot))))))
      (finally
        (try (publish! 'la-professeure.scene original)
             (finally
               (k/= scene/elapsed saved-time)
               (k/= scene/rendered-frames saved-frames)
               (scene/shutdown-world!)))))
    (is (nil? (a/value scene/world)))))

(deftest studio-reload-preserves-timing-history
  (when (or (some? (a/value studio/studio-window))
            (pos? (a/value studio/frame-timing-count)))
    (throw (ex-info "Run this check in its own nREPL, not a running editor" {})))
  (let [original (definition "la_professeure/tools/studio.clj" 'record-frame-timing!)
        render-ms '(k/floatCast (k/* 1000.0 (k/max 0.0 (k/- finished started))))]
    (try
      (studio/reset-frame-timings!)
      (studio/record-frame-timing! 1.0 1.125)
      (studio/record-frame-timing! 2.0 2.125)
      (is (= 1 (a/value studio/frame-timing-count)))
      (is (= 125.0 (:render_ms (a/value (a/get studio/frame-timings 0)))))
      (doseq [[index scale expected] [[1 2000.0 250.0] [2 4000.0 500.0]]]
        (publish! 'la-professeure.tools.studio
                  (walk/postwalk-replace
                   {render-ms (walk/postwalk-replace {1000.0 scale} render-ms)} original))
        (studio/record-frame-timing! (+ 2.0 index) (+ 2.125 index))
        (is (= (inc index) (a/value studio/frame-timing-count)))
        (is (= expected (:render_ms (a/value (a/get studio/frame-timings index)))))
        (is (= 125.0 (:render_ms (a/value (a/get studio/frame-timings 0))))))
      (publish! 'la-professeure.tools.studio original)
      (studio/record-frame-timing! 5.0 5.125)
      (is (= 4 (a/value studio/frame-timing-count)))
      (is (= 125.0 (:render_ms (a/value (a/get studio/frame-timings 3)))))
      (finally
        (try (publish! 'la-professeure.tools.studio original)
             (finally (studio/reset-frame-timings!)))))
    (is (zero? (a/value studio/frame-timing-count)))))
