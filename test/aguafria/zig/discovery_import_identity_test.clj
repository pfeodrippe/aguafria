(ns aguafria.zig.discovery-import-identity-test
  (:require [aguafria.keyword]
            [aguafria.zig]
            [aguafria.zig.discovery :as discovery]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(deftest inspection-reuses-dependencies-only-within-one-analysis
  (let [provider (create-ns (symbol (str "aguafria.inspection-provider-" (random-uuid))))
        consumer (create-ns (symbol (str "aguafria.inspection-consumer-" (random-uuid))))
        snapshot @#'runtime/static-dependency-snapshot
        snapshots (atom [])
        observe (fn [report]
                  (select-keys report [:operations :type-identities :compiler-errors?]))]
    (try
      (doseq [n [provider consumer]]
        (binding [*ns* n]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (alias 'k 'aguafria.keyword)))
      (binding [*ns* provider runtime/*source-only-registration?* true]
        (eval '(az/defconst amount :i32 1)))
      (binding [*ns* consumer runtime/*source-only-registration?* true]
        (alias 'provider (ns-name provider))
        (eval '(az/defn value :i32 [] (k/+ provider/amount 41))))
      (with-redefs-fn
        {#'runtime/static-dependency-snapshot
         (fn [& args]
           (let [result (apply snapshot args)]
             (swap! snapshots conj result)
             result))}
        (fn []
          (let [cached (discovery/analyze! (ns-name consumer))
                first-snapshot (first @snapshots)]
            (is (= 1 (count @snapshots)))
            (is (zero? (get-in cached [:baseline :exit])))
            (reset! snapshots [])
            (let [uncached (with-redefs [runtime/call-with-inspection-context
                                        (fn [_ f] (f))]
                             (discovery/analyze! (ns-name consumer)))]
              (is (< 1 (count @snapshots)))
              (is (= (observe cached) (observe uncached))))
            (binding [*ns* provider runtime/*source-only-registration?* true]
              (eval '(az/defconst amount :i32 2)))
            (reset! snapshots [])
            (let [updated (discovery/analyze! (ns-name consumer))]
              (is (= 1 (count @snapshots)))
              (is (not= first-snapshot (first @snapshots)))
              (is (zero? (get-in updated [:baseline :exit])))))))
      (finally
        (remove-ns (ns-name consumer))
        (remove-ns (ns-name provider))))))

(deftest imported-identities-keep-their-module-qualification
  (let [provider (create-ns (symbol (str "aguafria.identity-provider-" (random-uuid))))
        consumer (create-ns (symbol (str "aguafria.identity-consumer-" (random-uuid))))]
    (try
      (doseq [n [provider consumer]]
        (binding [*ns* n]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (alias 'k 'aguafria.keyword)))
      (binding [*ns* provider runtime/*source-only-registration?* true]
        (eval '(az/defstruct Device [[:number :i32]]))
        (eval '(az/defvar count :i32 7)))
      (binding [*ns* consumer runtime/*source-only-registration?* true]
        (alias 'provider (ns-name provider))
        (eval '(az/defn read-device :i32 [[device provider/Device]]
                 (k/+ (:number device) provider/count))))
      (let [report (discovery/analyze! (ns-name consumer))]
        (is (zero? (get-in report [:baseline :exit])) (get-in report [:baseline :diagnostics]))
        (is (false? (:compiler-errors? report)) (:diagnostics report))
        (is (seq (:operations report)))
        (is (every? #(= :observed (:status %)) (:operations report))))
      (finally
        (remove-ns (ns-name consumer))
        (remove-ns (ns-name provider))))))

(deftest inspection-preserves-root-module-c-include-options
  (let [context (create-ns (symbol (str "aguafria.include-probe-" (random-uuid))))
        configuration (runtime/configuration)]
    (try
      (runtime/configure!
       {:module-zig-args
        (assoc (:module-zig-args configuration) (str (ns-name context))
               [(str "-I" (.getAbsolutePath (io/file "test/aguafria/zig/fixtures")))])})
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'az 'aguafria.zig)
        (alias 'k 'aguafria.keyword)
        (eval '(az/defconst api (k/cImport (k/cInclude "inspection_options.h"))))
        (eval '(az/defn answer :i32 [] (:AGUAFRIA_INSPECTION_VALUE api))))
      (let [report (discovery/analyze! (ns-name context))]
        (is (zero? (get-in report [:baseline :exit])) (get-in report [:baseline :diagnostics]))
        (is (false? (:compiler-errors? report)) (:diagnostics report)))
      (finally
        (runtime/configure! configuration)
        (remove-ns (ns-name context))))))

(deftest cast-argument-probes-use-the-zig-callee-parameter-type
  (let [context (create-ns (symbol (str "aguafria.cast-probe-" (random-uuid))))]
    (try
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'az 'aguafria.zig)
        (alias 'k 'aguafria.keyword)
        (eval '(az/defn take-index :usize [[index :usize]] index))
        (eval '(az/defn take-float :f32 [[x :f32]] x))
        (eval '(az/defn cast-float :f32 [[index :u32]]
                 (take-float (k/floatFromInt index))))
        (eval '(az/defn cast-index :usize [[index :u32]]
                 (take-index (k/intCast index)))))
      (let [report (discovery/analyze! (ns-name context))
            operation (first (filter #(= "take-index" (name (:function %)))
                                     (:operations report)))]
        (is (zero? (get-in report [:baseline :exit])))
        (is (empty? (:probe-failures report)))
        (is (= :observed (:status operation)))
        (is (= [[:usize]] (:signatures operation))))
      (finally
        (remove-ns (ns-name context))))))

(deftest imported-result-aliases-stay-qualified-in-jvm-bridges
  (let [provider (create-ns (symbol (str "aguafria.alias-provider-" (random-uuid))))
        consumer (create-ns (symbol (str "aguafria.alias-consumer-" (random-uuid))))]
    (try
      (doseq [n [provider consumer]]
        (binding [*ns* n]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)))
      (binding [*ns* provider runtime/*source-only-registration?* true]
        (eval '(az/defstruct NativeRecord [[:number :i32]]))
        (eval '(az/defconst Record NativeRecord))
        (eval '(az/defconst Handle (az/type [:optional [:* NativeRecord]])))
        (eval '(az/defconst Opaque (az/opaque [])))
        (eval '(az/defconst OpaquePointer (az/type [:optional [:* Opaque]])))
        (eval '(az/defconst OpaqueHandle OpaquePointer)))
      (binding [*ns* consumer runtime/*source-only-registration?* true]
        (alias 'provider (ns-name provider))
        (eval '(az/defn make-record provider/Record []
                 (provider/Record {:number 42})))
        (eval '(az/defn absent-handle provider/Handle [] nil))
        (eval '(az/defn absent-opaque-handle provider/OpaqueHandle [] nil)))
      (is (= :prepared (:status (runtime/precompile-function!
                                 (ns-resolve consumer 'make-record)))))
      (is (= {:number 42}
             (aguafria.zig/value ((ns-resolve consumer 'make-record)))))
      (is (= :prepared (:status (runtime/precompile-function!
                                 (ns-resolve consumer 'absent-handle)))))
      (is (nil? (aguafria.zig/value ((ns-resolve consumer 'absent-handle)))))
      (is (= :prepared (:status (runtime/precompile-function!
                                 (ns-resolve consumer 'absent-opaque-handle)))))
      (is (nil? (aguafria.zig/value ((ns-resolve consumer 'absent-opaque-handle)))))
      (finally
        (remove-ns (ns-name consumer))
        (remove-ns (ns-name provider))))))

(deftest inspection-preserves-assignment-and-call-result-context
  (let [context (create-ns (symbol (str "aguafria.branch-probe-" (random-uuid))))]
    (try
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'az 'aguafria.zig)
        (alias 'k 'aguafria.keyword)
        (eval '(az/defconst channel-count :c_int 16))
        (eval '(az/defn take-count :u32 [[n :u32]] n))
        (eval '(az/defn branch-count :u32 [[flag :bool]]
                 (let [n (k/var 0 :u32)]
                   (k/= n channel-count)
                   (k/= n (if flag 16 2))
                   (k/+ n (take-count (if flag 4 0)))))))
      (let [report (discovery/analyze! (ns-name context))
            calls (filter #(= "take-count" (name (:function %))) (:operations report))
            assignments (filter :assignment (:operations report))]
        (is (zero? (get-in report [:baseline :exit])))
        (is (empty? (:probe-failures report)))
        (is (= [[:u32]] (:signatures (first calls))))
        (is (= 2 (count (set (map :form assignments)))))
        (is (= #{[[:u32 :u32]]} (set (map :signatures assignments)))))
      (finally
        (remove-ns (ns-name context))))))
