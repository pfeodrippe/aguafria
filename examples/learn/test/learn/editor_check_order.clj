(ns learn.editor-check-order
  "Select named Clojure examples in rendered HTML order without loading code."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Files]
           [java.security MessageDigest]))

(defn sha256 [file]
  (format "%064x" (java.math.BigInteger. 1
                                       (.digest (MessageDigest/getInstance "SHA-256")
                                                (Files/readAllBytes (.toPath (io/file file)))))))

(defn inventory
  [{:keys [html-file source-dir snippet-dir batch through-batch batch-size]
    :or {source-dir "resources/learn/example" snippet-dir "resources/learn/snippet" batch-size 50}}]
  (when (or (and batch through-batch)
            (not (pos-int? batch-size))
            (some #(and (some? %) (not (pos-int? %))) [batch through-batch])
            (and (not html-file) (or batch through-batch)))
    (throw (ex-info "Use :html-file with either :batch or :through-batch, and positive batch numbers/size"
                    {:batch batch :through-batch through-batch :batch-size batch-size})))
  (let [source-files (sort-by str (filter #(and (.isFile %) (str/ends-with? (.getName %) ".clj"))
                                         (file-seq (io/file source-dir))))
        captions (when html-file
                   (mapv second (re-seq #"<cite\s+class=\"file\">([^<>]+\.clj)</cite>" (slurp html-file))))
        names (vec (distinct captions))
        _ (when (and html-file (empty? names))
            (throw (ex-info "No named Clojure examples found in HTML" {:html-file html-file})))
        files (if html-file
                (mapv (fn [filename]
                        (when-not (= filename (.getName (io/file filename)))
                          (throw (ex-info "HTML example caption must be a filename" {:filename filename})))
                        (let [matches (filter #(.isFile %) [(io/file source-dir filename)
                                                           (io/file snippet-dir filename)])]
                          (when-not (= 1 (count matches))
                            (throw (ex-info "HTML example must map to exactly one source" {:filename filename
                                                                                         :matches (mapv str matches)})))
                          (first matches))) names)
                source-files)
        entries (mapv (fn [position file]
                        (with-open [r (java.io.PushbackReader. (io/reader file))]
                          (let [form (binding [*read-eval* false] (read r))]
                            (when-not (and (= 'ns (first form)) (symbol? (second form)))
                              (throw (ex-info "Expected namespace form" {:file (str file)})))
                            {:position position :batch (inc (quot (dec position) batch-size))
                             :namespace (second form) :file (.getCanonicalPath file)
                             :source-sha256 (sha256 file)}))) (range 1 (inc (count files))) files)
        selected (filterv #(cond batch (= batch (:batch %))
                                 through-batch (<= (:batch %) through-batch)
                                 :else true) entries)]
    (when (empty? selected)
      (throw (ex-info "Batch selection is empty" {:batch batch :through-batch through-batch :total (count entries)})))
    {:order (if html-file :html :filename) :html-file (some-> html-file io/file .getCanonicalPath)
     :html-sha256 (when html-file (sha256 html-file))
     :batch batch :through-batch through-batch :batch-size batch-size
     :total (count entries) :entries entries :selected selected
     :repeated-captions (into {} (filter #(> (val %) 1)) (frequencies captions))
     :unlisted-example-files (when html-file
                               (mapv str (remove #(contains? (set names) (.getName %)) source-files)))}))
