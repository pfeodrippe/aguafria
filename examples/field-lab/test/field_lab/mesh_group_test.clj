(ns field-lab.mesh-group-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [aguafria.zig :as az]
            [field-lab.coupled-cache-job :as job]
            [field-lab.mesh-cache :as cache]
            [field-lab.mesh-group :as group]
            [field-lab.mesh-cache-test :as mesh-test]
            [field-lab.scene :as scene]
            [field-lab.app :as app]))

(az/defn published-cache
  :- [:* cache/Cache]
  [[body :usize]]
  (az/unwrap (scene/mesh-cache-at body)))

(az/defn export-result
  :- :i32
  []
  (az/field app/controls exported))

(defn csv [file]
  (let [[header & lines] (str/split-lines (slurp file))
        columns (mapv keyword (str/split header #","))]
    (mapv #(zipmap columns (mapv parse-double (str/split % #","))) lines)))

(deftest synchronized-group-ownership-render-and-export
  (scene/initialize!)
  (try
    (let [result (job/build! {:refinement 1 :seconds 0.05})
          owned (:group result)
          expected (mapv #(az/value (cache/position (group/item owned %) 12 12)) (range 3))]
      (is (group/complete? owned))
      (scene/adopt-group! owned)
      (is (= 3 (az/value scene/body-count)))
      (is (= 13 (az/value scene/count)))
      (scene/seek! 12)
      (doseq [body (range 3)]
        (let [item (published-cache body)
              observation (:observation (az/value (cache/frame-info item 12)))]
          (is (= (expected body) (az/value (cache/position item 12 12))))
          (is (= (:center observation) (:position (az/value (scene/body-state body)))))))
      (is (apply < (map :x expected)))
      (is (false? (scene/step!)))
      (let [emission (az/value (mesh-test/measure-emission!))]
        (is (= 5760 (:vertices emission)))
        (is (< 0.999999 (:minimum-normal-length emission)))
        (is (> 1.000001 (:maximum-normal-length emission))))
      (scene/seek! 0)
      (scene/seek! 12)
      (is (= expected (mapv #(az/value (cache/position (published-cache %) 12 12)) (range 3))))
      (app/export!)
      (is (= 1 (export-result)))
      (let [reference (csv "exports/reference.csv")
            cells (csv "exports/cells.csv")
            particles (csv "exports/particles.csv")]
        (is (= 615 (count reference)))
        (is (= 1920 (count cells)))
        (is (= 7995 (count particles)))
        (is (= #{0.0 1.0 2.0} (set (map :body reference))))
        (is (= #{0.0 1.0 2.0} (set (map :body cells))))
        (doseq [body (range 3)]
          (let [row (first (filter #(and (= (double body) (:body %))
                                         (= 12.0 (:particle %)) (= 0.05 (:time_s %))) particles))]
            (is (some? row))
            (is (< (abs (- (:x (expected body)) (:x_m row))) 1.0e-12)))))
      (scene/set-solver! 0 10000.0)
      (is (nil? (az/value (scene/mesh-group))))
      (is (nil? (az/value (scene/mesh-cache))))
      (is (= 1 (az/value scene/count))))
    (finally (scene/shutdown!))))
