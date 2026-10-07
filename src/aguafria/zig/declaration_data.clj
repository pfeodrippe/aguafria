(ns aguafria.zig.declaration-data
  "EDN transport for declaration graphs, including shared source metadata."
  (:require [aguafria.zig.artifact :as artifact]
            [clojure.edn :as edn])
  (:import [java.util ArrayList IdentityHashMap]))

(defn encode
  "Store each distinct object once. Initializer metadata shares earlier forms;
  printing those forms as a tree would expand that sharing exponentially."
  [declaration]
  (let [indices (IdentityHashMap.)
        visiting (IdentityHashMap.)
        nodes (ArrayList.)]
    (letfn [(intern [value]
              (if (.containsKey indices value)
                (.get indices value)
                (do
                  (when (.containsKey visiting value)
                    (throw (ex-info "Cyclic Aguafria declaration data" {})))
                  (.put visiting value true)
                  (let [metadata (when-let [m (meta value)] (intern m))
                        [tag payload]
                        (cond
                          (map? value) [:map (mapv (fn [[k v]] [(intern k) (intern v)]) value)]
                          (vector? value) [:vector (mapv intern value)]
                          (set? value) [:set (mapv intern value)]
                          (seq? value) [:list (mapv intern value)]
                          :else [:value (if (instance? clojure.lang.IObj value)
                                          (with-meta value nil)
                                          value)])
                        index (.size nodes)]
                    (.add nodes [tag payload metadata])
                    (.put indices value index)
                    (.remove visiting value)
                    index))))]
      (let [root (intern declaration)]
        {:version 1 :root root :nodes (vec nodes)}))))

(defn decode
  "Restore the graph in dependency order, retaining both metadata and sharing."
  [{:keys [version root nodes]}]
  (when-not (and (= 1 version) (vector? nodes))
    (throw (ex-info "Invalid Aguafria declaration graph" {:version version})))
  (let [values (ArrayList.)
        reference (fn [index]
                    (when-not (and (integer? index) (<= 0 index) (< index (.size values)))
                      (throw (ex-info "Invalid Aguafria declaration reference" {:index index})))
                    (.get values (int index)))]
    (doseq [[tag payload metadata] nodes]
      (let [value (case tag
                    :map (into {} (map (fn [[k v]] [(reference k) (reference v)])) payload)
                    :vector (mapv reference payload)
                    :set (into #{} (map reference) payload)
                    :list (apply list (map reference payload))
                    :value payload
                    (throw (ex-info "Invalid Aguafria declaration node" {:tag tag})))
            value (if (some? metadata)
                    (with-meta value (reference metadata))
                    value)]
        (.add values value)))
    (reference root)))

(defn write-chunks
  "Encode declaration data in classfile-safe string constants."
  [declaration]
  (let [text (artifact/print-data (encode declaration))
        size 12000]
    (mapv #(subs text % (min (count text) (+ % size)))
          (range 0 (count text) size))))

(defn read-chunks [chunks]
  (when-not (and (vector? chunks) (every? string? chunks))
    (throw (ex-info "Serialized Aguafria declaration must be string chunks"
                    {:chunks (type chunks)})))
  (decode (edn/read-string (apply str chunks))))
