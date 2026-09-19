(ns aguafria.migrate-canonical-api
  "One-shot source migration, not an API compatibility layer.

  Run with Babashka (which bundles rewrite-clj):
    bb dev/aguafria/migrate_canonical_api.clj [--write] FILE...

  Defaults to a dry run. Only explicit files are considered. Reader forms and
  comments are preserved, with whitespace changes confined to declaration
  headers. Source strings are migrated when they contain a complete declaration.
  Review partial source snippets separately."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as node]
            [rewrite-clj.parser :as parser]))

(def ^:private operators
  #{'az/defn 'az/defn- 'aguafria.zig/defn 'aguafria.zig/defn-
    'zig/defn 'zig/defn-})

(defn- trivia? [n]
  (contains? #{:whitespace :newline :comment :comma} (node/tag n)))

(defn- value [n]
  (try (node/sexpr n) (catch Exception _ nil)))

(defn- groups [children]
  (loop [children children, pending [], result []]
    (if-let [n (first children)]
      (if (trivia? n)
        (recur (rest children) (conj pending n) result)
        (recur (rest children) [] (conj result [pending n])))
      [result pending])))

(declare migrate-source)

(defn- migrate-node [n counts]
  (let [n (if (node/inner? n)
            (node/replace-children n (mapv #(migrate-node % counts)
                                           (node/children n)))
            n)]
    (cond
      (= :list (node/tag n))
      (let [[entries trailing] (groups (node/children n))
            values (mapv (comp value second) entries)
            marker (.indexOf values :-)]
        (if (and (contains? operators (first values))
                 (<= 2 marker)
                 (< (+ marker 2) (count entries))
                 (= :vector (node/tag (second (nth entries (+ marker 2))))))
          (let [return-entry (nth entries (inc marker))
                discarded-trivia (mapcat first [(nth entries marker) return-entry])
                comments (when (some #(= :comment (node/tag %)) discarded-trivia)
                           discarded-trivia)
                prefix (concat (take 2 entries)
                               [[[(node/spaces 1)] (second return-entry)]]
                               (subvec entries 2 marker))
                flatten-entries (fn [xs]
                                  (mapcat (fn [[trivia child]]
                                            (conj (vec trivia) child)) xs))]
            (swap! counts update :functions (fnil inc 0))
            (node/replace-children
             n (concat (flatten-entries prefix)
                       comments
                       (flatten-entries (drop (+ marker 2) entries))
                       trailing)))
          n))

      (and (= :token (node/tag n))
           (string? (value n))
           (re-find #"\((?:az|zig|aguafria\.zig)/defn-?\s" (value n)))
      (let [original (value n)
            migrated (try (migrate-source original counts)
                          (catch Exception _ original))]
        (if (= original migrated) n (node/string-node migrated)))

      :else n)))

(defn migrate-source [source counts]
  (node/string (migrate-node (parser/parse-string-all source) counts)))

(defn -main [& args]
  (let [write? (= "--write" (first args))
        files (if write? (rest args) args)
        results (mapv
                 (fn [path]
                   (when (or (str/includes? path "/vendor/")
                             (str/includes? path "/.tmp/")
                             (str/includes? path "/build/"))
                     (throw (ex-info "Refusing excluded source tree" {:path path})))
                   (let [source (slurp path)
                         counts (atom {})
                         migrated (migrate-source source counts)]
                     {:path path :original source :migrated migrated :counts @counts}))
                 files)
        changed (filter #(not= (:original %) (:migrated %)) results)]
    ;; Parse every file before writing any file, and detect concurrent edits.
    (doseq [{:keys [path original]} changed]
      (when-not (= original (slurp path))
        (throw (ex-info "Source changed during migration" {:path path}))))
    (doseq [{:keys [path migrated counts]} changed]
      (when write? (spit path migrated))
      (println (if write? "MIGRATED" "WOULD-MIGRATE") path (pr-str counts)))
    (println (if write? "WROTE" "DRY-RUN") (count changed) "files")))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
