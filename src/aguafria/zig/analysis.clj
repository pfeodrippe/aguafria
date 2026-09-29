(ns aguafria.zig.analysis
  "Source spans joined to Zig-tool observations. Never infers types from Clojure."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rewrite-clj.node :as node]
            [rewrite-clj.parser :as parser])
  (:import [java.security MessageDigest]
           [java.util HexFormat]))

(defn source-hash [source]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes ^String source "UTF-8"))))

(defn analyze-source
  "Associate exact source spans with ZLS or compiler observations.
  No names, types, field layouts or call results are inferred here. Compiler
  observations must match the file and source revision. ZLS observations come
  from the emitter's exact source map, not identifier-name matching."
  [source {:keys [file observations zls-observations unavailable]
           :or {file "REPL" observations [] zls-observations []}}]
  (let [fingerprint (source-hash source)
        starts (vec (cons 0 (map inc (keep-indexed #(when (= %2 \newline) %1) source))))
        offset (fn [row col] (+ (nth starts (dec row)) (dec col)))
        compiler (group-by (juxt :line :column)
                           (filter #(and (= file (:file %))
                                         (= fingerprint (:source-sha256 %))
                                         (= :compile (:phase %))
                                         (= :ok (:status %))) observations))
        zls (group-by (juxt :start :end) zls-observations)]
    (letfn [(visit [n]
              (let [{:keys [row col end-row end-col]} (meta n)
                    start (offset row col)
                    end (offset end-row end-col)
                    types (vec (distinct (keep :type (get compiler [row col]))))
                    hovers (vec (distinct (keep :hover (get zls [start end]))))
                    result (cond
                             (seq types) {:basis :compiler :status :known :types types}
                             (seq hovers) {:basis :zls :status :known :message (str/join "\n\n" hovers)}
                             :else {:status :unresolved
                                    :message (or unavailable "No Zig-tool type result for this source span.")})]
                (cons (merge {:start start :end end :line row :column col
                              :end-line end-row :end-column end-col :form (node/string n)} result)
                      (when (node/inner? n)
                        (mapcat visit (filter node/sexpr-able? (node/children n)))))))]
      {:version 2 :file file :source-sha256 fingerprint
       :forms (->> (node/children (parser/parse-string-all source))
                   (filter node/sexpr-able?)
                   (mapcat visit)
                   (sort-by (juxt :start :end)) vec)})))

(defn file-report [file options]
  (analyze-source (slurp file) (assoc options :file (str file))))

(defn write-report! [report directory]
  (let [path (io/file directory (str (:source-sha256 report) ".edn"))]
    (io/make-parents path)
    (spit path (pr-str report))
    (str path)))
