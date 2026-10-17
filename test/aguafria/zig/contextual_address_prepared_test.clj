(ns aguafria.zig.contextual-address-prepared-test
  (:require [aguafria.zig]
            [aguafria.zig.check :as check]
            [aguafria.zig.discovery :as discovery]
            [aguafria.zig.jvm :as jvm]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(deftest contextual-address-observations-use-owning-construction-preparation
  (let [argument {:contextual-address {:tuple [{:literal 1 :type :comptime_int}
                                               {:literal 2 :type :comptime_int}]}}
        target [:slice-const :i32]
        prepared (atom [])
        operations (mapv (fn [[function conversion?]]
                           {:id (str function) :status :observed :function function
                            :constructor? true :conversion? conversion?
                            :signatures [[target argument]]})
                         [['aguafria.keyword/as true] ['aguafria.zig/init false]])
        report (with-redefs [discovery/analyze!
                            (fn [_] {:basis :zig-compiler
                                     :namespace 'aguafria.zig.contextual-address-prepared-test
                                     :operations operations})
                            jvm/precompile-construction!
                            (fn [type input]
                              (swap! prepared conj [type input]) {:status :prepared})
                            jvm/precompile-coercion!
                            (fn [& _] (throw (ex-info "Generic type preparation loses backing storage" {})))]
                 (with-redefs-fn {#'discovery/prepare-declared-functions! (constantly [])}
                   #(discovery/prepare! 'aguafria.zig.contextual-address-prepared-test)))]
    (is (= [[target argument] [target argument]] @prepared))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers (:operations report))))))

(deftest direct-contextual-address-conversion-survives-strict-restart
  (let [directory (str (Files/createTempDirectory "aguafria-contextual-address-"
                                                  (make-array java.nio.file.attribute.FileAttribute 0)))
        report (check/check!
                {:report-dir (str directory "/check")
                 :compilation-modes [:prepared] :strict-load? true
                 :parallelism 1 :heap-mib 768 :timeout-seconds 180
                 :precompile-options {:analyze ['aguafria.zig.contextual-address-prepared-fixture]
                                      :parallelism 16}
                 :scenarios [{:id :direct-address
                              :call 'aguafria.zig.contextual-address-prepared-driver/evaluate!
                              :args [] :expect {:value 3}}]})
        result (first (:results report))]
    (is (:accepted? report) (:directory report))
    (is (empty? (:final-compiler-attempts result)))
    (is (not-any? #(#{:compiled :compile-failed :disk-cache-hit} (:event %))
                  (:final-all-native-events result)))
    (is (some #(= :bundle-cache-hit (:event %)) (:final-all-native-events result)))))
