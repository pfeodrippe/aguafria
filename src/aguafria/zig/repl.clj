(ns aguafria.zig.repl
  "Source ranges for editor evaluation. Loading and evaluation belong to CIDER."
  (:require [clojure.data.json :as json]
            [rewrite-clj.node :as node]
            [rewrite-clj.parser :as parser])
  (:import [java.nio.charset StandardCharsets]
           [java.util Base64]))

(defn- children [n]
  (when (node/inner? n)
    (remove node/printable-only? (node/children n))))

(defn- token [n]
  (when (and n (= :token (node/tag n))) (node/sexpr n)))

(defn- documentation? [n]
  (or (#{:map :multi-line} (node/tag n)) (string? (token n))))

(defn- signature-tail [nodes]
  (drop-while documentation? nodes))

(defn plan
  "Locate native initializers and whole body forms in an already loaded namespace, without evaluating or
  reading any body through Clojure's reader. Tagged literals and reader macros
  remain source text. Ranges are one-based, end-exclusive, UTF-16 coordinates."
  [source namespace]
  (let [context (or (find-ns (symbol namespace))
                    (throw (ex-info "Load the namespace before planning" {:namespace namespace})))
        forms (children (parser/parse-string-all source))]
    (binding [*ns* context]
      {:namespace (str (ns-name context))
       :rows
       (vec
        (mapcat
         (fn [form]
           (when (= :list (node/tag form))
             (let [[head name & tail] (children form)
                   operator (when (symbol? (token head)) (some-> (resolve (token head)) meta))
                   owner (some-> operator :ns ns-name)
                   op (:name operator)
                   record (fn [body]
                            (let [{:keys [row col end-row end-col]} (meta body)]
                              {:declaration (node/string name)
                               :line row :column col :end-line end-row :end-column end-col
                               :expression (node/string body)}))]
               (cond
                 (and (= 'aguafria.zig owner) (#{'defconst 'defvar} op))
                 (let [parts (loop [parts tail]
                               (if (and (next parts) (documentation? (first parts)))
                                 (recur (next parts)) parts))
                       type (if (= 'defconst op)
                              (when (= 2 (count parts)) (first parts))
                              (when (and (next tail)
                                         (not (documentation? (first tail))))
                                (first tail)))]
                   [(cond-> (assoc (record (last tail)) :kind :initializer)
                      (and type (not (#{'_ :_} (token type))))
                      (assoc :type-context (node/string type)))])

                 (and (= 'aguafria.zig owner) (#{'defn 'defn-} op)
                      (#{:noreturn 'noreturn 'aguafria.keyword/noreturn} (token (first tail))))
                 [(assoc (record form) :status :skipped :reason :non-returning-function)]

                 (and (#{'aguafria.zig 'clojure.core} owner) (#{'defn 'defn-} op))
                 (let [signature (signature-tail (if (= 'aguafria.zig owner) (rest tail) tail))
                       signature (if (= :vector (some-> signature first node/tag)) signature
                                     (some (fn [arity]
                                             (let [parts (children arity)]
                                               (when (and (= :list (node/tag arity))
                                                          (= :vector (some-> parts first node/tag))
                                                          (empty? (children (first parts))))
                                                 parts)))
                                           signature))
                       [arguments & body] signature]
                   (if (and arguments (= :vector (node/tag arguments)) (empty? (children arguments)))
                     (if (and (= :map (some-> body first node/tag))
                              (some #{:pre :post} (map token (take-nth 2 (children (first body))))))
                       [(assoc (record form) :status :skipped :reason :function-contract)]
                       (map record body))
                     [(assoc (record form) :status :skipped :reason :requires-arguments)]))

                 (and (#{'aguafria.zig 'clojure.test} owner) (#{'deftest 'defcomptime} op))
                 (map record (signature-tail tail))

                 :else nil))))
         forms))})))

(defn plan-json
  "Metadata-only interface for the editor. No evaluation, output capture or session."
  [encoded]
  (let [{:keys [source namespace]}
        (json/read-str (String. (.decode (Base64/getDecoder) ^String encoded)
                                StandardCharsets/UTF_8) :key-fn keyword)]
    (json/write-str (plan source namespace))))
