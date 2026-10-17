(ns learn.editor-check-order-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [learn.editor-check-order :as order])
  (:import [java.nio.file Files]))

(defn- fixture []
  (let [root (str (Files/createTempDirectory "learn-html-order-" (make-array java.nio.file.attribute.FileAttribute 0)))
        source (str (io/file root "example"))
        snippets (str (io/file root "snippet"))
        html (str (io/file root "index.html"))
        ;; Deliberately reverse filename order and put a snippet at position 51.
        names (mapv #(format "%02d.clj" (- 56 %)) (range 1 56))]
    (doseq [[position filename] (map-indexed vector names)]
      (let [file (io/file (if (= position 50) snippets source) filename)]
        (io/make-parents file)
        (spit file (str "(ns lesson.n" (inc position) ")\n(throw (Exception. \"must not evaluate\"))"))))
    (spit html (apply str (map #(str "<cite class=\"file\">" % "</cite>") names)))
    {:html-file html :source-dir source :snippet-dir snippets}))

(deftest html-order-and-cumulative-regression-selection
  (let [options (fixture)
        first-batch (order/inventory (assoc options :batch 1))
        second-batch (order/inventory (assoc options :batch 2))
        cumulative (order/inventory (assoc options :through-batch 2))]
    (is (= 55 (:total first-batch)))
    (is (= 50 (count (:selected first-batch))))
    (is (= 'lesson.n1 (:namespace (first (:selected first-batch)))))
    (is (= 'lesson.n50 (:namespace (last (:selected first-batch)))))
    (is (= [51 52 53 54 55] (mapv :position (:selected second-batch))))
    (is (= 'lesson.n51 (:namespace (first (:selected second-batch)))))
    (is (= (:entries cumulative) (:selected cumulative)))
    (is (= [] (:unlisted-example-files first-batch)))
    (is (= 64 (count (:html-sha256 first-batch))))
    (is (every? #(= 64 (count (:source-sha256 %))) (:selected first-batch)))
    (spit (:html-file options) "<cite class=\"file\">missing.clj</cite>")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one source" (order/inventory options)))))

(deftest invalid-or-empty-batches-are-errors-not-green-empty-runs
  (let [options (fixture)]
    (doseq [extra [{:batch 0} {:batch 1 :through-batch 2} {:batch-size 0} {:batch 3}]]
      (is (thrown? clojure.lang.ExceptionInfo (order/inventory (merge options extra))))))
  (is (thrown? clojure.lang.ExceptionInfo (order/inventory {:batch 1}))))
