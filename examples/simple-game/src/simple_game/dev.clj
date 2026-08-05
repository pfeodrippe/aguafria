(ns simple-game.dev
  "Small JVM helpers for declaration-at-a-time native game development."
  (:require [aguafria.zig :as az]
            [clojure.java.io :as io]))

(defn- declaration-form
  [file declaration-name]
  (with-open [reader (clojure.lang.LineNumberingPushbackReader.
                      (io/reader file))]
    (loop []
      (let [form (read {:eof ::eof} reader)]
        (cond
          (= form ::eof)
          (throw (ex-info "Aguafria declaration not found"
                          {:name declaration-name
                           :file (str file)}))

          (= declaration-name (some-> form second name))
          form

          :else
          (recur))))))

(defn eval-declaration!
  "Evaluate and publish one named top-level Aguafria declaration.

  `file` is resolved from the current project directory. The return value is a
  compact timing/state summary suitable for Calva, CIDER, or terminal nREPLs."
  [ns-symbol file declaration-name]
  (let [started (System/nanoTime)
        form (declaration-form file declaration-name)]
    (binding [*ns* (the-ns ns-symbol)
              *file* (str file)]
      (eval form))
    (az/await! ns-symbol)
    (let [stats (az/stats ns-symbol)]
      {:namespace ns-symbol
       :declaration declaration-name
       :elapsed-ms (/ (double (- (System/nanoTime) started)) 1e6)
       :status (:status stats)
       :published-generation (:published-generation stats)
       :requested-generation (:requested-generation stats)})))
