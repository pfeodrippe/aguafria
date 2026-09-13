(ns field-lab.test-runner
  (:require [clojure.test :as test]
            [aguafria.zig :as az]
            [aguafria-examples-native.bindings]
            [aguafria-examples-native.imgui-bindings]
            [field-lab.build :as build]))

(def test-namespaces
  '[field-lab.physics-test
    field-lab.contacts-test
    field-lab.scene-test
    field-lab.soft-body-test
    field-lab.fem-test
    field-lab.hyperelastic-test
    field-lab.ball-fem-test
    field-lab.impact-study-test
    field-lab.mesh-cache-test
    field-lab.contact-mesh-test
    field-lab.coupled-fem-test
    field-lab.variational-test
    field-lab.mesh-group-test
    field-lab.spherical-source-test
    field-lab.readback-test])

(defn -main
  [& _]
  ;; Export checks call the native AguaFria export code but never open a native window.
  ;; Configure the extension host before loading tests that materialize app functions.
  (let [host (str (build/prepare-host! :dynamic-lib))
        arguments (vec (:zig-args (az/configuration)))]
    (az/configure! {:zig-args (if (some #{host} arguments) arguments (conj arguments host))}))
  (apply require test-namespaces)
  (let [result (apply test/run-tests test-namespaces)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
