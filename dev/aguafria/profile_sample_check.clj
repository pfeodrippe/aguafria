(ns aguafria.profile-sample-check
  "Post-profile correctness check using the maintained Learn consumer gates."
  (:require [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [learn.bundle-cache-check :as bodies]
            [learn.values-cache-check :as values]))

(def checked-lessons
  '[learn.example.test-pointer-casting
    learn.example.test-error-union
    learn.example.test-optional-type])

(defn check! [producer cache mode output]
  (runtime/configure! {:cache-dir cache})
  (let [report (edn/read-string (slurp producer))
        owners (mapv :test (mapcat :test-owners (:namespace-images report)))
        expected-bodies (count (mapcat :bodies (map #'bodies/read-lesson checked-lessons)))
        result (case mode
                 "values" (values/check! producer)
                 "bodies"
                 (with-redefs [bodies/checked-lessons checked-lessons
                               bodies/checked-constants []
                               bodies/checked-native-owners owners
                               bodies/expected-body-count expected-bodies]
                   (bodies/check! producer))
                 (throw (ex-info "Expected values or bodies mode" {:mode mode})))]
    (spit (io/file output) (pr-str result))
    (prn (dissoc result :results :output :artifact-events))
    result))

(defn -main [producer cache mode output]
  (try (check! producer cache mode output) (finally (shutdown-agents))))
