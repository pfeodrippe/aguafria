(ns aguafria.zig.source-map-test
  (:require [aguafria.zig.source-map :as source-map]
            [aguafria.zig.emitter :as emitter]
            [clojure.test :refer [deftest is]]))

(deftest exact-spans-survive-nested-emission
  (let [source "(let [x 1234] x)"
        form (first (source-map/read-forms source))
        names [(first (second form)) (last form)]
        spans (atom [])
        rendered (binding [source-map/*spans* spans]
                   (str "const " (emitter/identifier (first names)) " = 1234;\n"
                        (emitter/identifier (second names))))
        {:keys [source mappings]} (source-map/extract rendered @spans)]
    (is (= "const x = 1234;\nx" source))
    (is (= [6 14] (mapv :start mappings)))
    (is (= [6 16] (mapv :zig-start mappings)))
    (is (every? #(= "x" (subs source (:zig-start %) (:zig-end %))) mappings))))

(deftest preserve-reader-semantics-and-identifier-metadata
  (let [source "(let [é 1 ^:var x 2] [@x 'é {:a x} #{x}])"
        form (first (source-map/read-forms source))]
    (is (= (read-string source) form))
    (is (:var (meta (nth (second form) 2))))
    (is (= "x" (let [{:keys [start end]} (:aguafria/span (meta (second (first (last form)))))]
                 (subs source start end))))))
