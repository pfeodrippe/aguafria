(ns learn.bundle-cache-check
  "Explicit fresh-JVM check of ordinary lesson bodies against a prepared pack."
  (:require [aguafria.zig :as a]
            [aguafria.zig.explain :as explain]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [clojure.lang LineNumberingPushbackReader]))

(def checked-lessons
  '[learn.example.test-coerce-optional-wrapped-error-union
    learn.example.test-thread-local-variables
    learn.example.test-coerce-slices-arrays-and-pointers
    learn.example.test-slices
    learn.example.test-inline-switch
    learn.example.test-allocator
    learn.example.test-comptime-evaluation
    learn.example.test-error-union
    learn.example.test-optional-type
    learn.example.test-peer-type-resolution
    learn.example.test-functions
    learn.example.test-switch-dispatch-loop
    learn.example.test-tagged-union
    learn.example.test-switch-modify-tagged-union
    learn.example.test-inferred-error-sets
    learn.example.test-merging-error-sets
    learn.example.test-while-else
    learn.example.result-type-propagation
    learn.example.test-aligned-struct-fields
    learn.example.test-packed-struct-equality
    learn.example.test-packed-union-equality
    learn.example.test-switch-tagged-union
    learn.example.destructuring-mixed
    learn.example.destructuring-to-existing
    learn.example.destructuring-block
    learn.example.destructuring-return-value])

(def checked-main-lessons
  '#{learn.example.destructuring-mixed
     learn.example.destructuring-to-existing
     learn.example.destructuring-block
     learn.example.destructuring-return-value})

(defn test-bodies
  "Return each authored top-level test body, preserving its lexical scope."
  [forms]
  (into []
        (mapcat (fn [[_ declaration & body]]
                  (map-indexed (fn [index form]
                                 {:declaration declaration :index index :form form})
                               (drop-while #(or (string? %) (map? %)) body))))
        (filter #(and (seq? %) (symbol? (first %))
                      (= "deftest" (name (first %)))) forms)))

(defn main-bodies
  "Keep each selected parameterless main body together, including its bindings."
  [forms]
  (mapv (fn [[_ declaration result-type & signature]]
          (let [[parameters & body] (drop-while #(or (string? %) (map? %)) signature)]
            (when-not (and (= :void result-type) (= [] parameters))
              (throw (ex-info "Only parameterless void lesson mains are checked"
                              {:declaration declaration :parameters parameters
                               :result-type result-type})))
            {:declaration declaration :index 0 :form (cons 'do body)}))
        (filter #(and (seq? %) (symbol? (first %))
                      (= "defn" (name (first %))) (= 'main (second %))) forms)))

(defn- read-lesson [namespace]
  (let [path (str (-> (str namespace)
                      (str/replace "." "/")
                      (str/replace "-" "_")) ".clj")
        resource (or (io/resource path)
                     (throw (ex-info "Lesson resource not found" {:source path})))]
    (with-open [reader (LineNumberingPushbackReader. (io/reader resource))]
      (let [forms (binding [*read-eval* false]
                    (into [] (take-while some?)
                          (repeatedly #(read {:eof nil} reader))))]
        {:source path
         :bodies (into (test-bodies forms)
                       (when (contains? checked-main-lessons namespace)
                         (main-bodies forms)))}))))

(defn validate-report!
  "Reject failed assertion results, runtime builds and the wrong producer pack."
  [{:keys [results expected-body-count producer-bundle-id bundles
           compiled standalone events] :as report}]
  (let [bodies (mapcat :bodies results)
        failures (filterv #(or (not= :passed (:status %))
                               (not (contains? #{nil {:ok nil}} (:result %))))
                          bodies)]
    (when (or (not= expected-body-count (count bodies)) (seq failures))
      (throw (ex-info "Ordinary lesson bodies failed"
                      {:failures failures :expected-body-count expected-body-count
                       :actual-body-count (count bodies)})))
    (when (or (seq compiled) (seq standalone))
      (throw (ex-info "Ordinary bodies missed the prepared bundle"
                      {:compiled compiled :standalone standalone})))
    (when-not (and (string? producer-bundle-id)
                   (= #{producer-bundle-id} bundles)
                   (= 1 (:bundle-loaded events)))
      (throw (ex-info "Fresh JVM did not load exactly the producer pack"
                      (select-keys report [:producer-bundle-id :bundles :events]))))
    report))

(defn- evaluate-body [{:keys [form] :as body}]
  (try
    (let [result (a/value (eval form))]
      (assoc (dissoc body :form)
             :status (if (contains? #{nil {:ok nil}} result) :passed :failed)
             :result result))
    (catch Throwable error
      (assoc (dissoc body :form) :status :failed
             :causes (mapv ex-message
                           (take-while some? (iterate ex-cause error)))))))

(defn check!
  "Run the selected safe bodies in a fresh JVM after explicit precompilation."
  [producer-report]
  (let [loaded (filterv find-ns checked-lessons)]
    (when (seq loaded)
      (throw (ex-info "Run the bundle check in a fresh JVM" {:loaded loaded}))))
  (let [producer (edn/read-string (slurp producer-report))
        _ (when-not (= 1 (count (get-in producer [:bundles :packs])))
            (throw (ex-info "Producer report must contain one bundle"
                            {:producer-report producer-report})))
        events (atom [])
        started (System/nanoTime)
        results (binding [explain/*reporter* #(swap! events conj %)]
                  (mapv (fn [namespace]
                          (require namespace)
                          (let [{:keys [source bodies]} (read-lesson namespace)]
                            {:namespace namespace
                             :bodies (binding [*ns* (the-ns namespace) *file* source]
                                       (mapv evaluate-body bodies))}))
                        checked-lessons))
        report {:producer-report producer-report
                :expected-body-count 56
                :producer-bundle-id (get-in producer [:bundles :packs 0 :id])
                :results results
                :duration-ms (/ (- (System/nanoTime) started) 1e6)
                :events (frequencies (map :event @events))
                :bundles (set (keep :bundle-id @events))
                :compiled (filterv #(#{:compiled :compile-failed} (:event %)) @events)
                :standalone (filterv #(and (= :disk-cache-hit (:event %))
                                           (str/starts-with? (str (:module %))
                                                             "aguafria.jvm."))
                                     @events)}]
    (validate-report! report)))

(defn -main [producer-report & _]
  (when-not producer-report
    (throw (ex-info "Supply the Learn precompile report path" {})))
  (try
    (let [report (check! producer-report)]
      (prn (assoc (dissoc report :results)
                  :bodies (frequencies (map :status (mapcat :bodies (:results report)))))))
    (finally (shutdown-agents))))
