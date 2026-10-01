(ns la-professeure.core-test
  (:require [aguafria.zig :as az]
            [clojure.test :refer [deftest is]]
            [la-professeure.core :as core]))

(deftest render-loop-recovers-from-standard-compilation-errors
  (let [failure (clojure.lang.Compiler$CompilerException.
                 "scene.clj" 10 3 (ex-info "Invalid native edit" {}))
        ticks (atom 0)
        observed (atom nil)
        shutdowns (atom 0)
        status (atom {})
        native {'initialize! (constantly true)
                'tick! #(case (swap! ticks inc)
                          1 (throw failure)
                          2 (do (reset! observed @status) true)
                          false)
                'snapshot (constantly {:rendered_frames 2})
                'shutdown! #(swap! shutdowns inc)}
        resolve-original ns-resolve]
    (with-redefs [core/status status
                  core/commands (java.util.concurrent.ConcurrentLinkedQueue.)
                  ns-resolve (fn [namespace name]
                               (if (= namespace 'la-professeure.scene)
                                 (get native name)
                                 (resolve-original namespace name)))
                  az/value identity
                  az/close! (constantly nil)]
      (#'core/run-stage!))
    (is (= :runtime-error (:state @observed)))
    (is (identical? failure (:error @observed)))
    (is (= 3 @ticks))
    (is (= 1 @shutdowns))
    (is (= :closed (:state @status)))))
