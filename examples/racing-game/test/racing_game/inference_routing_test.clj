(ns racing-game.inference-routing-test
  (:require [aguafria.zig :as az]
            [clojure.test :refer [deftest is]]
            [racing-game.inference :as inference]
            [racing-game.protocol :as protocol]))

(deftest every-driver-keeps-the-eight-action-head
  ;; An out-of-vocabulary token prevents inference or state mutation even if
  ;; weights are loaded. The report still exposes the selected action space.
  (let [drivers (az/value protocol/racer-count)
        actors (az/value protocol/actor-count)]
    (doseq [actor (range actors)]
      (let [report (az/value (inference/forward-token! actor 4294967295))]
        (is (false? (:valid report)))
        (is (= (if (< actor drivers) 8 3) (:candidate_count report))
            (str "Wrong action head for actor " actor))))))
