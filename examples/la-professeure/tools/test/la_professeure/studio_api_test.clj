(ns la-professeure.studio-api-test
  (:require [clojure.test :refer [deftest is]]
            [la-professeure.tools.studio :as studio]))

(defn- isolated [f]
  (with-redefs [studio/worker (atom :test-worker)
                studio/command-queue (java.util.concurrent.ArrayBlockingQueue. 64)
                studio/request-ledger (atom {}) studio/completed-requests (atom [])
                studio/event-log (atom {:sequence 0 :events []}) studio/extensions (atom {})]
    (f)))

(deftest explicit-recording-control-contract
  (isolated
    #(do
       (doseq [command [{:op :record/arm :args {:id "voice-test" :enabled true}}
                        {:op :record/enable :args {:enabled true}}
                        {:op :playback/boost :args {:enabled true}}
                        {:op :record/fx} {:op :record/dry} {:op :record/toggle}]]
         (is (= :queued (:status (studio/submit! command)))))
       (doseq [command [{:op :record/enable :args {:enabled 1}}
                        {:op :playback/boost :args {:enabled "yes"}}
                        {:op :record/arm :args {:id "voice-test"}}
                        {:op :record/arm :args {:id 12 :enabled true}}
                        {:op :routing/select :args {:source -1 :send 0 :return 0 :headphones 0}}]]
         (is (thrown? clojure.lang.ExceptionInfo (studio/submit! command)))))))

(deftest command-validation-and-capabilities
  (isolated
    #(do
       (is (= true (:multitrack? (studio/capabilities))))
       (is (= false (:arranger? (studio/capabilities))))
       (is (contains? (:commands (studio/capabilities)) :transport/pause))
       (is (true? (get-in (studio/capabilities) [:mix :loop?])))
       (doseq [command [{:op :unknown/command} {:op :transport/seek :args {:seconds Double/NaN}}
                        {:op :transport/seek :args {:seconds -1}} {:op :view/zoom :args {:factor 0}}
                        {:op :take/trim :args {:from 50 :to 10}} {:op :transport/play :args {:typo true}}
                        {:op :mix/loop :args {:from 2 :to 1 :enabled true}}
                        {:op :mix/loop :args {:from 1 :to 1 :enabled true}}
                        {:op :mix/loop :args {:from Double/NaN :to 2 :enabled true}}
                        {:op :mix/loop :args {:from 0 :to 2 :enabled "yes"}}]]
         (is (thrown? clojure.lang.ExceptionInfo (studio/submit! command))))
       (reset! studio/worker nil)
       (is (= :closed (try (studio/submit! {:op :transport/play}) (catch Exception e (:code (ex-data e)))))))))

(deftest optimistic-project-revision
  (isolated
    #(with-redefs [studio/project (atom {:revision 12}) studio/session (atom nil) studio/armed (atom nil)]
       (studio/register-command! :test/revision {:description "Revision fixture" :validate map? :handler identity})
       (is (= :revision-conflict
              (try (#'studio/execute-command! {:op :test/revision :args {} :expected-revision 11})
                   (catch Exception e (:code (ex-data e))))))
       (is (:accepted? (#'studio/execute-command! {:op :test/revision :args {} :expected-revision 12})))
       (is (thrown? Exception (studio/submit! {:op :project/undo :expected-revision -1})))
       (is (thrown? Exception (studio/submit! {:op :project/undo :expected-revision 1.5}))))))

(deftest bounded-idempotent-requests
  (isolated
    #(let [command {:op :transport/play :request-id "same"}]
       (is (= :queued (:status (studio/submit! command))))
       (is (= :known (:status (studio/submit! command))))
       (is (= 1 (.size studio/command-queue)))
       (is (= :pending (:status (studio/result "same"))))
       (is (= :request-conflict (try (studio/submit! (assoc command :op :transport/stop))
                                    (catch Exception e (:code (ex-data e))))))
       (let [ticket (.poll studio/command-queue)]
         (#'studio/complete-request! ticket {:status :done})
         (is (= :done (:status (studio/result "same"))))
         (studio/submit! command)
         (is (zero? (.size studio/command-queue))))
       (dotimes [i 64] (studio/submit! {:op :transport/play :request-id (str i)}))
       (is (= :queue-full (try (studio/submit! {:op :transport/play}) (catch Exception e (:code (ex-data e))))))
       (is (= 65 (count @studio/request-ledger)))
       (is (= :unknown (:status (studio/result "not-found")))))))

(deftest extension-and-event-contract
  (isolated
    #(do
       (studio/register-command! :test/hello {:description "Test extension" :validate map? :handler identity})
       (is (= "Test extension" (get-in (studio/capabilities) [:commands :test/hello :description])))
       (is (nil? (get-in (studio/capabilities) [:commands :test/hello :handler])))
       (is (thrown? clojure.lang.ExceptionInfo
                    (studio/register-command! :transport/play {:description "Override" :validate map? :handler identity})))
       (studio/unregister-command! :test/hello)
       (is (thrown? clojure.lang.ExceptionInfo (studio/submit! {:op :test/hello})))
       (dotimes [i 300] (#'studio/emit-event! {:type :test :value i}))
       (is (= 256 (count (:events (studio/events-since 0)))))
       (is (:resync? (studio/events-since 0)))
       (is (= 300 (:cursor (studio/events-since 299))))
       (is (= [299] (mapv :value (:events (studio/events-since 299)))))
       (is (false? (:resync? (studio/events-since 299))))
       (is (:resync? (studio/events-since 301))))))

(deftest recording-rejection-and-extension-result
  (isolated
    #(do
       (with-redefs [studio/session (atom {:id "retained-take"}) studio/armed (atom nil)]
         (is (= :recording-busy
                (try (#'studio/execute-command! {:op :transport/play :args {}})
                     (catch Exception e (:code (ex-data e))))))
         (is (= {:id "retained-take"} @studio/session)))
       (with-redefs [studio/session (atom nil) studio/armed (atom nil)]
         (studio/register-command! :test/echo {:description "Echo" :validate map? :handler identity})
         (is (= {:ok true} (:result (#'studio/execute-command! {:op :test/echo :args {:ok true}}))))))))

(deftest synchronous-extension-recursion-is-rejected
  (isolated
    #(binding [studio/*command-worker?* true]
       (is (= :reentrant-command
              (try (studio/command! {:op :transport/play})
                   (catch Exception e (:code (ex-data e))))))
       (is (zero? (.size studio/command-queue)))
       ;; Deferred follow-up work does not block the worker and remains supported.
       (is (= :queued (:status (studio/submit! {:op :transport/stop})))))))
