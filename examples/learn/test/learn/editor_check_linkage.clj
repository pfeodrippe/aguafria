(ns learn.editor-check-linkage
  "Compile-only companion artifacts required by the authored Learn examples.
  No main/test entry is invoked, and consumers only read the frozen receipt."
  (:require [aguafria.zig :as a]
            [aguafria.zig.build :as build]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [learn.editor-check-order :as order]))

(defn prepare! [inventory receipt-file]
  (when (.exists (io/file receipt-file))
    (throw (ex-info "Linkage receipt already exists" {:file receipt-file})))
  (let [selected (set (map :namespace (:selected inventory)))
        object-ns 'learn.example.float-mode-obj
        executable-ns 'learn.example.float-mode-exe
        artifact (when (selected executable-ns)
                   ;; Match the build recipe in float_mode_exe.clj's comment.
                   (binding [runtime/*compile-only?* true]
                     (build/load-source-only! object-ns)
                     (let [artifact (a/build! object-ns {:kind :object :optimize "fast"
                                                       :zig-args ["-fPIC"]})]
                       (assoc artifact :sha256 (order/sha256 (:output-path artifact))))))
        receipt {:schema :learn-editor-linkage-v1
                 :artifacts (cond-> [] artifact (conj artifact))
                 :configuration (if artifact
                                  {:zig-args-by-module {(str executable-ns) [(:output-path artifact)]}}
                                  {})
                 ;; Selection explicitly owns these namespaces. Permit their
                 ;; authored C exports to retain ABI names in dedicated images;
                 ;; ordinary non-exporting graphs still use shared packs. This
                 ;; does not manufacture external libraries or linker inputs.
                 :linkage-contexts (vec (sort-by str selected))}]
    (io/make-parents receipt-file)
    (spit receipt-file (pr-str receipt))
    receipt))

(defn read! [receipt-file]
  (let [receipt (edn/read-string (slurp receipt-file))]
    (when-not (= :learn-editor-linkage-v1 (:schema receipt))
      (throw (ex-info "Unknown Learn linkage receipt" {:file receipt-file})))
    (doseq [{:keys [output-path sha256]} (:artifacts receipt)]
      (when-not (and (.isFile (io/file output-path)) (= sha256 (order/sha256 output-path)))
        (throw (ex-info "Prepared companion artifact is missing or changed"
                        {:file output-path :receipt receipt-file}))))
    receipt))
