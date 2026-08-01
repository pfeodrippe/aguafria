(ns aguafria.test-runner
  (:require [aguafria.keywords-test]
            [aguafria.zig.emitter-test]
            [aguafria.zig-integration-test]
            [clojure.test :as test]))

(def test-namespaces
  '[aguafria.keywords-test
    aguafria.zig.emitter-test
    aguafria.zig-integration-test])

(defn -main
  [& _]
  (let [{:keys [fail error] :as result} (apply test/run-tests test-namespaces)]
    (shutdown-agents)
    (when (pos? (+ fail error))
      (binding [*out* *err*]
        (println "Tests failed:" (select-keys result [:test :pass :fail :error])))
      (System/exit 1))))
