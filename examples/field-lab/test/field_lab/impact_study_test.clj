(ns field-lab.impact-study-test
  (:require [clojure.test :refer [deftest is]]
            [field-lab.impact-study :as study]))

(deftest completed-impact-and-impulse-accounting
  (let [coarse (study/run-case! {:maximum-step 0.00001 :sample-dt 0.001})
        fine (study/run-case! {:maximum-step 0.000005 :sample-dt 0.001})
        comparison (study/compare-cases coarse fine)]
    (doseq [result [coarse fine]
            :let [summary (:summary result)]]
      (is (:contact-free-at-end? summary))
      (is (pos? (:final-center-velocity-y-m-s summary)))
      (is (< (:momentum-balance-error-N-s summary) 1.0e-10))
      (is (zero? (:minimum-clearance-m summary)))
      (is (> (:minimum-jacobian summary) 0.5))
      (is (< (:maximum-energy-gain-fraction summary) 1.0e-6)))
    (is (< (:center-trajectory-max-difference-m comparison) 1.0e-5))
    (is (< (:final-velocity-difference-m-s comparison) 1.0e-3))))

(deftest unfinished-impact-is-not-reported-as-separated
  (let [result (study/run-case! {:seconds 0.0005 :sample-dt 0.00025})
        summary (:summary result)]
    (is (false? (:contact-free-at-end? summary)))
    (is (nil? (:first-contact-bracket-s summary)))
    (is (zero? (:normal-impulse-N-s summary)))))

(deftest comparisons-reject-different-output-times
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"identical sample times"
                       (study/compare-cases {:samples [{:time-s 0.0} {:time-s 0.1}]}
                                            {:samples [{:time-s 0.0} {:time-s 0.2}]}))))
