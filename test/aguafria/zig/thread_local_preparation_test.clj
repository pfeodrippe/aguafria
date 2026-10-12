(ns aguafria.zig.thread-local-preparation-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.value :as value]
            [clojure.test :refer [deftest is]]))

(a/defvar counter :i32 {:attrs #{k/threadlocal}} 1234)

(defn- on-thread [virtual? f]
  (let [result (promise)
        task (fn [] (deliver result (try {:value (f)} (catch Throwable t {:error t}))))
        thread (if virtual? (Thread/startVirtualThread task) (doto (Thread. task) (.start)))]
    (.join thread 30000)
    (deref result 1000 {:timeout true})))

(deftest descriptor-inspection-does-not-access-thread-local-storage
  (let [calls (atom 0)
        handle (value/native-value {:kind :var :type :i32 :threadlocal? true}
                                   #(do (swap! calls inc) {:representation :scalar :value 7}))
        result (on-thread true #(vector
                                 (value/qualified-type handle)
                                 (:status (value/info handle))
                                 (try (value/realize! handle)
                                      (catch clojure.lang.ExceptionInfo e (ex-data e)))))]
    (is (= {:value [:i32 :pending {:type :native-thread-local-storage}]} result))
    (is (zero? @calls))
    (is (= 7 (a/value handle)))
    (is (= :ready (:status (value/info handle))))
    (is (= {:value :pending} (on-thread true #(:status (value/info handle)))))
    (is (= 1 @calls))))

(deftest native-tls-addresses-are-resolved-on-each-platform-thread
  (k/= counter 4321)
  (try
    (let [result (on-thread false #(let [before (a/value counter)]
                                     (k/= counter 2468)
                                     [before (a/value counter)]))]
      (is (= {:value [1234 2468]} result))
      (is (= 4321 (a/value counter))))
    (finally (k/= counter 1234))))
