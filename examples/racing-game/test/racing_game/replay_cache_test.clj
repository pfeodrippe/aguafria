(ns racing-game.replay-cache-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.discovery :as discovery]
            [aguafria.zig.explain :as explain]
            [aguafria.zig.precompile :as precompile]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.value :as value]
            [clojure.test :refer [deftest is]]
            [racing-game.simulation :as simulation]))

(a/defn native-replay-size-bounds [:array 2 :isize]
  []
  (a/array
   [(k/as (k/intCast simulation/replay-file-header-bytes) :isize)
    (k/as (k/intCast
           (k/+ simulation/replay-file-header-bytes
                (k/* simulation/replay-capacity simulation/replay-file-entry-bytes)))
          :isize)]
   :isize))

(deftest typed-replay-size-bounds-work-in-ordinary-jvm-calls
  (let [bounds (fn []
                 [(k/as (k/intCast simulation/replay-file-header-bytes) :isize)
                  (k/as (k/intCast
                         (k/+ simulation/replay-file-header-bytes
                              (k/* simulation/replay-capacity
                                   simulation/replay-file-entry-bytes)))
                        :isize)])]
    (let [[lower upper] (bounds)]
      (with-open [lower lower
                  upper upper]
        (is (= 32 (a/value lower)))
        (is (= 40992 (a/value upper)))
        (is (= :isize (value/qualified-type lower)))
        (is (= :isize (value/qualified-type upper)))))
    (let [events (atom [])]
      (binding [explain/*reporter* #(swap! events conj %)]
        (doseq [bound (bounds)]
          (with-open [bound bound]
            (a/value bound))))
      (is (zero? (count (filter #(= :compiled (:event %)) @events)))
          (pr-str @events)))
    (with-open [native (native-replay-size-bounds)]
      (is (= [32 40992] (a/value native))))))

(deftest replay-size-bounds-prepare-without-running-the-native-body
  (let [fail! (fn [& _] (throw (ex-info "Preparation invoked native code" {})))
        report (binding [runtime/*compile-only?* true]
                 (with-redefs [runtime/invoke! fail!
                               runtime/invoke-with-result! fail!]
                   (discovery/prepare! 'racing-game.replay-cache-test)))
        coverage (precompile/coverage [report])]
    (is (zero? (get-in coverage [:namespaces :baseline-failures])))
    (is (= {:prepared 1} (get-in coverage [:declared-functions :statuses])))
    (is (= {:total 5 :fully-prepared 5 :not-fully-prepared 0}
           (:runtime-candidates coverage)))
    (is (zero? (get-in coverage [:handler-records :failed] 0)))
    (is (= 2 (get-in coverage [:deferred-calls :total])))))
