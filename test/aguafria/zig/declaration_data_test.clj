(ns aguafria.zig.declaration-data-test
  (:require [aguafria.zig.declaration-data :as data]
            [clojure.test :refer [deftest is]]))

(defn- chained-initializers [length]
  (reduce (fn [previous index]
            (let [initializer (list '+ previous previous)]
              (with-meta (symbol (str "value" index))
                {:line (inc index)
                 :aguafria/jvm-initializer initializer
                 :aguafria/jvm-binding-source {:initializer initializer :variable? false}})))
          (with-meta 'input {:line 1 :aguafria/zig-source {:file "fixture.clj"}})
          (range length)))

(deftest shared-initializers-have-linear-transport-size
  (let [short (data/encode (chained-initializers 20))
        long (data/encode (chained-initializers 80))
        restored (data/read-chunks (data/write-chunks (chained-initializers 80)))]
    (is (< (count (:nodes long)) (* 5 (count (:nodes short)))))
    (is (< (reduce + (map count (data/write-chunks (chained-initializers 80)))) 60000))
    (loop [binding restored index 79]
      (if (neg? index)
        (is (= {:line 1 :aguafria/zig-source {:file "fixture.clj"}} (meta binding)))
        (let [initializer (:aguafria/jvm-initializer (meta binding))]
          (is (= (symbol (str "value" index)) binding))
          (is (= (inc index) (:line (meta binding))))
          (is (identical? initializer
                          (get-in (meta binding) [:aguafria/jvm-binding-source :initializer])))
          (is (identical? (second initializer) (last initializer)))
          (recur (second initializer) (dec index)))))))

(deftest transport-preserves-collections-leaves-and-metadata
  (let [leaf (with-meta 'fixture/value {:line 8 :column 3})
        original (with-meta {:vector [leaf "λ" nil true 42 1/3 1.5 \h]
                             :list (list :a leaf)
                             :set #{:one :two}}
                   {:source "fixture.clj"})
        restored (binding [*print-length* 1 *print-level* 1 *print-meta* true
                           *print-dup* true *print-readably* false]
                   (data/read-chunks (data/write-chunks original)))]
    (is (= original restored))
    (is (= (meta original) (meta restored)))
    (is (= (meta leaf) (meta (first (:vector restored)))))
    (is (identical? (first (:vector restored)) (second (:list restored))))
    (is (list? (:list restored)))))

(deftest declaration-graph-rejects-invalid-references
  (is (thrown? clojure.lang.ExceptionInfo (data/decode {:version 2 :root 0 :nodes []})))
  (is (thrown? clojure.lang.ExceptionInfo
               (data/decode {:version 1 :root 0 :nodes [[:vector [0] nil]]})))
  (is (thrown? clojure.lang.ExceptionInfo (data/read-chunks "not chunks"))))
