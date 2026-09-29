(ns aguafria.zig.source-map
  "Exact source spans carried through emission; no type inference."
  (:require [rewrite-clj.node :as node]
            [rewrite-clj.parser :as parser]))

(def ^:dynamic *spans* nil)

(defn mark [form rendered]
  (if-let [span (and *spans* (:aguafria/span (meta form)))]
    (let [id (count (swap! *spans* conj span))]
      (str "\u0001" id "\u0002" rendered "\u0003" id "\u0004"))
    rendered))

(defn extract [rendered spans]
  (let [pattern (re-matcher #"[\u0001\u0003]([0-9]+)[\u0002\u0004]" rendered)
        output (StringBuilder.)]
    (loop [previous 0 opened {} mappings []]
      (if (.find pattern)
        (let [id (Long/parseLong (.group pattern 1))
              _ (.append output (subs rendered previous (.start pattern)))
              position (.length output)]
          (if (= \u0001 (.charAt rendered (.start pattern)))
            (recur (.end pattern) (assoc opened id position) mappings)
            (recur (.end pattern) (dissoc opened id)
                   (conj mappings (assoc (nth spans (dec id))
                                         :zig-start (get opened id) :zig-end position)))))
        (do (.append output (subs rendered previous))
            {:source (str output) :mappings mappings})))))

(defn read-forms
  "Read forms with exact offsets on each metadata-capable node. Never evaluates."
  [source]
  (let [starts (vec (cons 0 (map inc (keep-indexed #(when (= %2 \newline) %1) source))))
        offset (fn [row col] (+ (nth starts (dec row)) (dec col)))]
    (letfn [(read-node [n]
              (let [parts (when (node/inner? n) (filter node/sexpr-able? (node/children n)))
                    value (case (node/tag n)
                            :list (apply list (map read-node parts))
                            :vector (mapv read-node parts)
                            :map (apply array-map (map read-node parts))
                            :set (set (map read-node parts))
                            :deref (list 'clojure.core/deref (read-node (last parts)))
                            :quote (list 'quote (read-node (last parts)))
                            :meta (with-meta (read-node (last parts)) (meta (node/sexpr n)))
                            (node/sexpr n))
                    {:keys [row col end-row end-col]} (meta n)]
                (if (instance? clojure.lang.IObj value)
                  (vary-meta value assoc :line row :column col
                             :aguafria/span {:start (offset row col) :end (offset end-row end-col)
                                             :line row :column col})
                  value)))]
      (mapv read-node (filter node/sexpr-able? (node/children (parser/parse-string-all source)))))))
