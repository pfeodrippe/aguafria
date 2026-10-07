(ns tigerbeetle-agua.hot-reload-benchmark-test
  (:require [aguafria.zig :as a]
            [aguafria.zig.benchmark :as benchmark]
            [clojure.test :refer [deftest is testing]]
            [tigerbeetle-agua.hot-reload-benchmark :as hot]))

(deftest scalar-verification-snapshots-and-releases-its-result
  (let [handle (Object.)
        closed (atom [])]
    (with-redefs [a/value (fn [actual]
                            (is (identical? handle actual))
                            10)
                  a/close! #(swap! closed conj %)]
      (is (= {:value 10} (#'hot/verified-value "scalar" 10 handle)))
      (is (= [handle] @closed))
      (reset! closed [])
      (let [failure (try (#'hot/verified-value "mismatch" 12 handle)
                         nil
                         (catch clojure.lang.ExceptionInfo error error))]
        (is (= {:label "mismatch" :expected 12 :actual 10} (ex-data failure)))
        (is (= [handle] @closed)))))
  (testing "ordinary JVM scalar snapshots keep the fixture usable"
    (is (= {:value 10} (#'hot/verified-value "already-decoded" 10 10)))))

(deftest queue-size-expectation-is-decoded-before-publication
  (let [created (atom [])
        closed (atom [])
        size (fn []
               (let [handle (Object.)]
                 (swap! created conj handle)
                 handle))]
    (with-redefs [clojure.core/requiring-resolve
                  (fn [symbol]
                    (case symbol
                      tigerbeetle-agua.hot-reload-queue-target/queue-size size
                      tigerbeetle.src.queue/QueueType #'identity))
                  a/value (fn [handle]
                            (is (some #(identical? handle %) @created))
                            64)
                  a/close! #(swap! closed conj %)
                  benchmark/measure-edit!
                  (fn [{:keys [verify-change verify-restore]}]
                    (is (= 1 (count @closed)) "initial expectation is already an immutable snapshot")
                    {:changed (verify-change) :restored (verify-restore)})]
      (is (= {:changed {:value 64} :restored {:value 64}} (hot/complex!)))
      (is (= 3 (count @created)))
      (is (= @created @closed)))))
