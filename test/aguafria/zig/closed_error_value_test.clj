(ns aguafria.zig.closed-error-value-test
  (:require [aguafria.zig :as a]
            [aguafria.zig.value :as value]
            [aguafria.zig.jvm :as jvm]
            [clojure.test :refer [deftest is]])
  (:import [java.lang.foreign Arena ValueLayout]))

(a/defn widen [:error-set [:OtherFailure :OutOfMemory]]
  [[error [:error-set [:OutOfMemory]]]]
  error)

(a/defn echo-error [:error-set [:OtherFailure :OutOfMemory]]
  [[error [:error-set [:OtherFailure :OutOfMemory]]]]
  error)

(deftest bare-errors-cross-call-boundaries-by-name
  (let [error (value/->ZigError "OutOfMemory" [:error-set [:OutOfMemory]])
        widened (widen error)]
    (is (= "OutOfMemory" (:name (a/value widened))))
    (is (= "OutOfMemory" (:name (a/value (echo-error widened))))))
  (is (= "OtherFailure"
         (:name (a/value (echo-error (value/->ZigError "OtherFailure" [:error-set [:OtherFailure]]))))))
  (is (= :error-name-not-in-closed-set
         (try
           (widen (value/->ZigError "ForeignError" [:error-set [:ForeignError]]))
           nil
           (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a raw ordinal"
                        (widen 17))))

(deftest error-member-order-does-not-fragment-named-source-adapters
  (let [left (value/->ZigError "OutOfMemory" [:error-set [:OtherFailure :OutOfMemory]])
        right (value/->ZigError "OutOfMemory" [:error-set [:OutOfMemory :OtherFailure]])]
    (is (= (#'jvm/named-error-form left) (#'jvm/named-error-form right)))
    (is (= (#'jvm/canonical-comptime-source-operands (value/error-form left))
           (#'jvm/canonical-comptime-source-operands (value/error-form right))))))

(deftest foreign-storage-translates-names-even-with-one-layout-reader
  (with-open [arena (Arena/ofConfined)]
    (let [source (.allocate arena 8 8)
          destination (.allocate arena 8 8)
          names (atom [])
          schema {:kind :error-set :error-set [:OutOfMemory]
                  :error-fn (fn [_] {:name "OutOfMemory" :code 17})
                  :set-error-fn (fn [storage error-name]
                                  (swap! names conj error-name)
                                  (.set storage ValueLayout/JAVA_LONG 0 91))}]
      (.set source ValueLayout/JAVA_LONG 0 17)
      (value/copy-native! destination schema source schema
                          {:source-image "old-image" :destination-image "reloaded-image"})
      (is (= ["OutOfMemory"] @names))
      (is (= 91 (.get destination ValueLayout/JAVA_LONG 0)))
      (reset! names [])
      (value/copy-native! destination schema source schema
                          {:source-image "same-image" :destination-image "same-image"})
      (is (empty? @names))
      (is (= 17 (.get destination ValueLayout/JAVA_LONG 0)))
      (value/copy-native! destination schema source schema)
      (is (= ["OutOfMemory"] @names)))))
