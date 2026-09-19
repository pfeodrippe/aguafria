(ns field-lab.impact-study-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [aguafria.zig :as az]
            [field-lab.nonlinear-job :as job]
            [pitoco.geometry :as geometry]
            [field-lab.impact-study :as study]
            [field-lab.variational :as implicit]
            [field-lab.coupled-job :as joint]))

(deftest native-inertia-audit-matches-solid-simplex-integrals
  ;; Uniform unit simplex: mass=20, centroid=(1/4,1/4,1/4),
  ;; covariance diagonal=3/80 and off-diagonal=-1/80.
  (doseq [scale [1.0 2.0]
          translation [[0.0 0.0 0.0] [33554432.0 -16777216.0 8388608.0]]]
    (let [points [[0.0 0.0 0.0] [1.0 0.0 0.0] [0.0 1.0 0.0] [0.0 0.0 1.0]]
          positions (mapv #(mapv + translation (mapv (partial * scale) %)) points)
          ;; Spin about the current center at omega=(0,0,2), plus translation.
          velocities (mapv (fn [[x y _]]
                             [(- 1.0 (* 2.0 scale (- y 0.25)))
                              (+ 2.0 (* 2.0 scale (- x 0.25))) 3.0]) points)
          description {:mesh {:points points :cells [[0 1 2 3]]}
                       :material {:young-Pa 1000.0 :poisson-ratio 0.3}
                       :density-kg-m3 120.0 :gravity [0.0 0.0 0.0] :floor? false
                       :initial-positions positions :initial-velocities velocities}
          audit (job/with-state! description
                  (fn [state _]
                    (let [first (az/value (study/inertia-audit state 120.0))]
                      (is (= first (az/value (study/inertia-audit state 120.0))))
                      first)))
          close? (fn [expected actual] (< (abs (- expected actual)) 1e-10))
          square (* scale scale)]
      (is (close? 20.0 (:integrated-mass audit)))
      (is (close? 20.0 (:nodal-mass audit)))
      (is (close? 4.0 (:inertia-relative-frobenius-error (study/inertia-comparison audit))))
      (is (every? #(close? 0.0 %) (vals (:nodal-center-offset audit))))
      (doseq [axis [:x :y :z]]
        (is (close? (* 1.5 square) (get-in audit [:integrated-inertia :diagonal axis])))
        (is (close? (* 0.25 square) (get-in audit [:integrated-inertia :off-diagonal axis])))
        (is (close? (* 7.5 square) (get-in audit [:nodal-inertia :diagonal axis]))))
      (is (close? (+ 140.0 (* 3.0 square)) (:integrated-kinetic-energy audit)))
      (is (close? (+ 140.0 (* 15.0 square)) (:nodal-kinetic-energy audit)))
      (doseq [[axis momentum angular] [[:x 20.0 0.5] [:y 40.0 0.5] [:z 60.0 3.0]]]
        (is (close? momentum (get-in audit [:integrated-momentum axis])))
        (is (close? momentum (get-in audit [:nodal-momentum axis])))
        (is (close? (* angular square) (get-in audit [:integrated-angular-momentum axis])))))))

(deftest native-inertia-audit-integrates-a-sheared-box
  ;; x'=x+y/2, y'=y, z'=z. Reference covariance diag=(1/3,3/4,4/3).
  ;; Current covariance xx=25/48, yy=3/4, zz=4/3, xy=3/8.
  (let [mesh (geometry/box-mesh [2 3 2] [2.0 3.0 4.0])
        description {:mesh mesh :material {:young-Pa 1000.0 :poisson-ratio 0.3}
                     :density-kg-m3 5.0 :gravity [0.0 0.0 0.0] :floor? false
                     :initial-positions (mapv (fn [[x y z]] [(+ x (* 0.5 y)) y z]) (:points mesh))}
        audit (job/with-state! description
                (fn [state _] (az/value (study/inertia-audit state 5.0))))]
    (doseq [[axis expected] [[:x 250.0] [:y 222.5] [:z 152.5]]]
      (is (< (abs (- expected (get-in audit [:integrated-inertia :diagonal axis]))) 1e-10)))
    (is (< (abs (+ 45.0 (get-in audit [:integrated-inertia :off-diagonal :x]))) 1e-10))
    (is (< (abs (get-in audit [:integrated-inertia :off-diagonal :y])) 1e-10))
    (is (< (abs (get-in audit [:integrated-inertia :off-diagonal :z])) 1e-10))
    (is (zero? (:integrated-kinetic-energy audit)))))

(deftest all-surface-scalar-redistribution-must-preserve-radius-moment
  (let [mesh (geometry/box-mesh [2 2 2] [2.0 2.0 2.0])
        description {:mesh mesh :material {:young-Pa 1000.0 :poisson-ratio 0.3}
                     :density-kg-m3 1.0 :gravity [0.0 0.0 0.0] :floor? false}
        result (study/audit-inertia! description)
        bound (study/surface-massless-radius-bound result)]
    ;; All surface nodes massless leaves only the cube's center. No positive
    ;; scalar mass there can reproduce the nonzero inertia of the solid cube.
    (is (= 1 (:interior-nodes bound)))
    (is (= 26 (:surface-nodes bound)))
    (is (< (abs (- 1.0 (:required-mean-squared-radius-m2 bound))) 1e-12))
    (is (< (:maximum-interior-squared-radius-m2 bound) 1e-24))
    (is (false? (:necessary-radius-bound-satisfied? bound)))))

(deftest force-audit-excludes-rejections-and-retains-failure-prefix
  (let [force (atom 0.0)
        simulate (fn [_ _ _ _ _ {:keys [maximum-attempts on-progress]}]
                   (is (= 1 maximum-attempts))
                   (reset! force 2.0)
                   (on-progress {:substeps 1 :time 0.5 :ground-impulse 1.0})
                   ;; Failed trial: no accepted time/impulse, and a deliberately
                   ;; invalid gradient value that must not enter the record.
                   (reset! force 1e20)
                   (on-progress {:substeps 1 :time 0.5 :ground-impulse 1.0 :rejected 1})
                   (throw (ex-info "Injected solve failure" {:status 5})))]
    (with-redefs [implicit/advance! simulate
                  implicit/net-contact-force (fn [_] {:y @force})
                  az/value identity]
      (try
        (study/advance-with-force-audit! nil 1.0 0.5 1e-6 1000.0 {} 0.0)
        (is false "Injected failure must propagate")
        (catch clojure.lang.ExceptionInfo error
          (is (= 5 (:status (ex-data error))))
          (is (= [{:begin-s 0.0 :end-s 0.5 :impulse-N-s 1.0 :endpoint-force-N 2.0}]
                 (:accepted-force-steps (ex-data error)))))))))

(deftest force-audit-detects-spikes-cancelled-by-output-averaging
  (let [steps (mapv (fn [index impulse]
                      {:begin-s (* 0.5 index) :end-s (* 0.5 (inc index))
                       :impulse-N-s impulse :endpoint-force-N (* 2.0 impulse)})
                    (range 4) [0.0 1.0 0.0 1.0])
        run {:reference {:impact-time-s 0.0 :release-time-s 2.0 :contact-force-N 1.0
                         :total-contact-impulse-N-s 2.0}
             :samples [{:time-s 2.0 :contact-impulse-N-s 2.0 :report {:substeps 4}
                        :accepted-force-steps steps}]}]
    ;; The one output bin has exactly the reference impulse. The four half-second
    ;; step averages alternate between zero and twice the reference force.
    (with-redefs [study/summarize-rod-impact (constantly {:force-pulse-relative-L1-error 0.0})]
      (let [result (study/force-audit-summary run)]
        (is (= 1.0 (:step-average-force-relative-L1-error result)))
        (is (= 1.0 (:error-hidden-by-output-averaging result)))
        (is (= 2.0 (:maximum-step-average-force-ratio result)))
        (is (zero? (:time-coverage-error-s result)))
        (is (zero? (:output-impulse-mismatch-N-s result))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing accepted steps"
                           (study/force-audit-summary (assoc-in run [:samples 0 :report :substeps] 5)))))))

(deftest accepted-force-audit-preserves-native-trajectory
  (joint/with-compiled-kernel! :ipc
    (fn []
      (let [options {:divisions [1 8 1] :steps-per-contact 512 :samples-per-contact 32
                     :clearance-m 1e-6 :gap-m 2e-6 :integration :bdf2}
            plain (study/run-rod-impact! options)
            audited (study/run-rod-impact! (assoc options :audit-forces? true))
            result (study/force-audit-summary audited)]
        (is (= (:inertia-audit plain) (:inertia-audit audited)))
        (is (< (abs (- 0.04 (get-in audited [:inertia-audit :initial :integrated-mass]))) 1e-14))
        (is (= (:samples plain) (mapv #(dissoc % :accepted-force-steps) (:samples audited))))
        (is (< (abs (:output-impulse-mismatch-N-s result)) 1e-15))
        (is (< (abs (:time-coverage-error-s result)) 1e-13))
        (is (>= (:error-hidden-by-output-averaging result) -1e-12))
        (doseq [sample (:samples audited)]
          (is (< (abs (- (get-in sample [:report :ground-impulse])
                         (reduce + (map :impulse-N-s (:accepted-force-steps sample))))) 1e-15)))))))

(deftest rod-section-diagnostics-distinguish-axial-and-warped-fields
  (let [mesh (geometry/box-mesh [2 3 2] [0.02 0.1 0.02])
        base {:mesh mesh :material {:young-Pa 1e6 :poisson-ratio 0.0}
              :density-kg-m3 1000.0 :gravity [0.0 0.0 0.0] :floor? false}
        inspect (fn [description]
                  (job/with-state! description
                    (fn [state _] (az/value (study/rod-fields state 2 3 2)))))
        axial (inspect (assoc base :initial-velocities
                              (mapv (fn [[_ y _]] [0.0 (* 3.0 y) 0.0]) (:points mesh))))
        warped (inspect (assoc base
                               :initial-positions (mapv (fn [[x y z]] [x (+ y (* 0.1 z)) z]) (:points mesh))
                               :initial-velocities (mapv (fn [[_ y z]] [0.02 (+ (* 3.0 y) z) 0.0]) (:points mesh))))]
    (is (every? zero? (vals axial)))
    (is (< (abs (- 0.02 (:maximum-section-velocity-spread warped))) 1e-12))
    (is (< (abs (- 0.002 (:maximum-section-height-spread warped))) 1e-12))
    (is (< (abs (- 0.02 (:maximum-transverse-speed warped))) 1e-12))
    ;; Uniform transverse translation: 1/2 * rho * volume * vx^2.
    (is (< (abs (- 8e-6 (:transverse-kinetic-energy warped))) 1e-16))))

(deftest rod-comparison-refuses-confounded-controls
  (let [baseline {:settings {:divisions [1 32 1] :steps-per-contact 2048 :clearance-m 2.5e-7 :gap-m 2e-6}
                  :solver-version {:fingerprint "one"} :reference {:mass-kg 0.04}
                  :samples [{:time-s 0.001}]
                  :summary {:force-pulse-relative-L1-error 0.2 :final-rebound-speed-ratio 0.9
                            :mechanical-energy-loss-fraction 0.03 :maximum-relative-center-velocity-error 0.1}}
        temporal (assoc-in baseline [:settings :steps-per-contact] 4096)]
    (is (= :steps-per-contact (:control (study/compare-rod-control baseline temporal))))
    (is (every? zero? (vals (:summary-differences (study/compare-rod-control baseline temporal)))))
    (doseq [confounded [(assoc-in temporal [:settings :divisions] [1 64 1])
                        (assoc-in baseline [:settings :gap-m] 1e-6)
                        baseline]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one numerical control"
                           (study/compare-rod-control baseline confounded))))
    (doseq [incompatible [(assoc temporal :solver-version {:fingerprint "two"})
                         (assoc temporal :reference {:mass-kg 0.05})
                         (assoc temporal :samples [{:time-s 0.002}])]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"identical solver"
                           (study/compare-rod-control baseline incompatible))))))

(defn mixed-history-fixture [boundaries forces steps]
  (let [impulses (reductions + (map (fn [[begin end] force] (* (- end begin) force))
                                  (partition 2 1 boundaries) forces))]
    {:status :complete :settings {:steps-per-contact steps :integration :sdirk2}
     :description {:mesh :fixture} :initial {:velocity -1.0} :solver-version {:fixture "one"}
     :reference {:impact-time-s 0.0 :release-time-s 2.0 :contact-force-N 1.0
                 :total-contact-impulse-N-s 2.0}
     :maximum-step-s (apply max (map - (rest boundaries) boundaries))
     :samples (mapv (fn [[begin end] force impulse]
                      {:begin-s begin :time-s end :average-contact-force-N force
                       :contact-impulse-N-s impulse})
                    (partition 2 1 boundaries) forces impulses)}))

(deftest mixed-time-comparison-conserves-impulse-and-exposes-hidden-ringing
  (let [coarse (mixed-history-fixture [0.0 1.0 2.0] [1.0 1.0] 2)
        fine (mixed-history-fixture [0.0 0.5 1.0 1.5 2.0] [0.0 2.0 0.0 2.0] 4)
        result (study/compare-mixed-time-refinement coarse fine)]
    (is (= 1.0 (:force-observation-window-s result)))
    (is (zero? (:force-history-relative-L1-difference result)))
    (is (zero? (:fine-common-window-force-error result)))
    (is (= 1.0 (:fine-accepted-step-force-error result)))
    (is (= 1.0 (:fine-error-hidden-by-averaging result)))
    (is (zero? (:fine-impulse-rebinning-error-N-s result)))
    (is (= [1.0 1.0] (mapv :fine-force-N (:trace result))))
    (let [short-coarse (mixed-history-fixture [0.0 1.0 1.75] [1.0 1.0] 2)
          short-fine (mixed-history-fixture [0.0 0.5 1.0 1.5 1.75] [1.0 1.0 1.0 1.0] 4)]
      (is (zero? (:fine-impulse-rebinning-error-N-s
                   (study/compare-mixed-time-refinement short-coarse short-fine)))))))

(deftest mixed-time-comparison-rejects-confounded-or-corrupt-evidence
  (let [coarse (mixed-history-fixture [0.0 1.0 2.0] [1.0 1.0] 2)
        fine (mixed-history-fixture [0.0 0.5 1.0 1.5 2.0] [1.0 1.0 1.0 1.0] 4)]
    (doseq [invalid [(assoc fine :status :failed)
                     (assoc fine :samples [])
                     (assoc-in fine [:samples 1 :begin-s] 0.6)
                     (assoc-in fine [:samples 1 :contact-impulse-N-s] 7.0)
                     (assoc-in fine [:samples 1 :average-contact-force-N] Double/NaN)
                     (assoc-in fine [:solver-version :fixture] "two")
                     (dissoc fine :solver-version)
                     (assoc-in fine [:description :mesh] :different)
                     (assoc-in fine [:initial :velocity] -2.0)
                     (assoc-in fine [:settings :integration] :backward-euler)
                     (assoc-in fine [:settings :steps-per-contact] 3)
                     (mixed-history-fixture [0.0 0.4 0.8 1.4 2.0] [1.0 1.0 1.0 1.0] 4)]]
      (is (thrown? clojure.lang.ExceptionInfo (study/compare-mixed-time-refinement coarse invalid))))))

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

(deftest rod-characteristic-reference-and-pulse-integration
  (let [reference (study/rod-impact-reference
                   {:length-m 0.1 :width-m 0.02 :young-Pa 1e6
                    :density-kg-m3 1000.0 :speed-m-s 0.01 :gap-m 2e-7})
        impact (:impact-time-s reference)
        release (:release-time-s reference)
        close? (fn [a b] (< (abs (- a b)) 1e-12))]
    (is (close? 0.04 (:mass-kg reference)))
    (is (close? 31.622776601683793 (:wave-speed-m-s reference)))
    (is (close? 0.006324555320336759 (:contact-duration-s reference)))
    (is (close? 0.1264911064067352 (:contact-force-N reference)))
    (is (close? 0.0008 (:total-contact-impulse-N-s reference)))
    (is (close? -0.01 (study/rod-reference-velocity reference 0.0)))
    (is (close? 0.0 (study/rod-reference-velocity reference (* 0.5 (+ impact release)))))
    (is (close? 0.01 (study/rod-reference-velocity reference (* 2.0 release))))
    ;; Uneven bins straddle both discontinuities; their integrals must reproduce
    ;; momentum reversal, independently of where sample boundaries fall.
    (let [boundaries [0.0 (* impact 0.5) (* impact 3.0) (* release 0.7) (* release 1.4)]
          impulse (reduce + (map (fn [[begin end]]
                                   (* (- end begin) (study/rod-reference-average-force reference begin end)))
                                 (partition 2 1 boundaries)))]
      (is (close? (* 2.0 (:mass-kg reference) (:speed-m-s reference)) impulse)))
    (is (zero? (study/rod-reference-average-force reference 0.0 (* impact 0.5))))
    (is (zero? (study/rod-reference-average-force reference (* 1.1 release) (* 1.2 release))))
    (is (thrown? clojure.lang.ExceptionInfo (study/rod-reference-average-force reference 1.0 1.0)))))

(deftest rod-study-retains-prefix-and-refuses-overwrite
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "pitoco-rod-study-" (make-array java.nio.file.attribute.FileAttribute 0)))
        output (io/file directory "failure.edn")
        success (io/file directory "success.edn")
        options {:output (.getPath output) :cases [{:case-id 1} {:case-id 2}]}]
    (try
      ;; Fault injection tests persistence independently of the physical benchmark.
      (with-redefs [joint/with-compiled-kernel! (fn [method run] (is (= :ipc method)) (run))
                    study/run-rod-impact!
                    (fn [{:keys [case-id on-sample]}]
                      (on-sample {:time-s 0.001 :case-id case-id})
                      (if (= case-id 2)
                        (throw (ex-info "Injected nonlinear failure" {:status 3}))
                        {:case-id case-id :samples [{:time-s 0.001}]}))
                    study/summarize-rod-impact (fn [result] {:case-id (:case-id result)})]
        (binding [*out* (java.io.StringWriter.)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Injected nonlinear failure"
                               (study/rod-study! options)))
          (let [saved (slurp output)
                record (edn/read-string saved)]
            (is (= :failed (:status record)))
            (is (= 3 (get-in record [:failure :status])))
            (is (= [1] (mapv :case-id (:cases record))))
            (is (= [{:time-s 0.001 :case-id 2}] (:partial-samples record)))
            (is (= 2 (get-in record [:active-case :case-id])))
            (is (re-matches #"[0-9a-f]{64}" (:experiment-source-sha256 record)))
            (is (thrown? java.nio.file.FileAlreadyExistsException (study/rod-study! options)))
            (is (= saved (slurp output))))
          (is (= :completed (:status (study/rod-study! {:output (.getPath success) :cases [{:case-id 1}]}))))
          (let [record (edn/read-string (slurp success))]
            (is (= :completed (:status record)))
            (is (= 1 (count (:cases record))))
            (is (not (contains? record :partial-samples))))
          (is (= #{"failure.edn" "success.edn"} (set (.list directory))))))
      (finally
        (doseq [file (reverse (file-seq directory))] (.delete ^java.io.File file))))))

(deftest rod-benchmark-rejects-mismatched-assumptions
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown rod benchmark settings"
                       (study/run-rod-impact! {:poisson-ratio 0.3})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid rod contact"
                       (study/run-rod-impact! {:integration :unimplemented})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"linear rod reference"
                       (study/run-rod-impact! {:speed-m-s 1.0})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid rod contact"
                       (study/run-rod-impact! {:divisions [1 8 1 4]})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"outside the contact potential"
                       (study/run-rod-impact! {:gap-m 1e-8 :clearance-m 1e-7}))))

(deftest mixed-rod-failure-is-never-a-completed-study
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "pitoco-mixed-rod-" (make-array java.nio.file.attribute.FileAttribute 0)))
        output (io/file directory "failed.edn")
        sample {:time-s 0.001 :accepted true}]
    (try
      (with-redefs [joint/with-compiled-kernel! (fn [method run] (is (= :mixed method)) (run))
                    study/run-mixed-rod-impact!
                    (fn [{:keys [on-sample]}]
                      (on-sample sample)
                      {:status :failed :failed-time-s 0.002 :failed-report {:status 4}
                       :samples [sample]})
                    study/summarize-mixed-rod-impact (fn [result] {:status (:status result)})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"accepted prefix saved"
                             (study/rod-study! {:benchmark :mixed-rod-impact :cases [{}]
                                                :output (.getPath output)})))
        (let [record (edn/read-string (slurp output))]
          (is (= :failed (:status record)))
          (is (= :failed (get-in record [:cases 0 :status])))
          (is (= 4 (get-in record [:failure :failed-report :status])))
          (is (= [sample] (:partial-samples record)))
          (is (= [sample] (get-in record [:cases 0 :samples])))
          (is (= :failed (get-in record [:cases 0 :summary :status])))))
      (finally
        (doseq [file (reverse (file-seq directory))] (.delete ^java.io.File file))))))
