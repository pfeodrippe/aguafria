(ns la-professeure.studio-api-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [la-professeure.core :as core]
            [la-professeure.tools.studio :as studio]))

(deftest window-bounds-fit-available-displays
  (let [main {:x 0 :y 25 :width 1728 :height 1067}
        left {:x -1920 :y 0 :width 1920 :height 1080}
        frame {:left 0 :top 28 :right 0 :bottom 0}
        saved {:x 343 :y 168 :width 1100 :height 760}]
    (is (= saved (studio/fit-window-bounds saved [main] frame)))
    (is (= {:x 0 :y 53 :width 1728 :height 1039}
           (studio/fit-window-bounds {:x -5000 :y -2000 :width 9000 :height 7000} [main] frame)))
    (is (= {:x 628 :y 332 :width 1100 :height 760}
           (studio/fit-window-bounds (assoc saved :x 4000 :y 2000) [main] frame)))
    (is (= {:x -1800 :y 100 :width 1100 :height 760}
           (studio/fit-window-bounds (assoc saved :x -1800 :y 100) [main left] frame)))
    (is (= {:x 0 :y 100 :width 1100 :height 760}
           (studio/fit-window-bounds (assoc saved :x -1800 :y 100) [main] frame)))
    (is (= 1100 (:width (studio/fit-window-bounds (assoc saved :width 10) [main] frame))))
    (doseq [bad [(assoc saved :width -1) (assoc saved :x Double/NaN) (assoc saved :y "200") nil]]
      (is (thrown? clojure.lang.ExceptionInfo (studio/fit-window-bounds bad [main] frame))))
    (is (= :display-too-small
           (try (studio/fit-window-bounds saved [{:x 0 :y 0 :width 800 :height 600}] frame)
                (catch clojure.lang.ExceptionInfo e (:code (ex-data e))))))))

(deftest window-preferences-debounce-and-normal-bounds
  (let [path (java.nio.file.Files/createTempFile "studio-window-qa-" ".edn"
               (make-array java.nio.file.attribute.FileAttribute 0))
        file (.toFile path) state (atom nil)
        normal {:x 10 :y 50 :width 1100 :height 760 :normal 1}
        read! #(edn/read-string (slurp file))]
    (try
      (studio/save-window-preferences! normal file state 0 false)
      (is (zero? (.length file)) "Don't write on every motion event")
      (studio/save-window-preferences! normal file state 499999999 false)
      (is (zero? (.length file)))
      (studio/save-window-preferences! normal file state 500000000 false)
      (is (= {:version 1 :bounds (dissoc normal :normal)} (read!)))
      (let [saved @state]
        (studio/save-window-preferences! (assoc normal :normal 0 :width 1728) file state 1000000000 true)
        (is (= saved @state) "Maximized/minimized geometry is ignored even at close"))
      (studio/save-window-preferences! (assoc normal :width 1400) file state 1000000000 false)
      (is (= 1100 (get-in (read!) [:bounds :width])))
      (studio/save-window-preferences! (assoc normal :width 1400) file state 1000000001 true)
      (is (= 1400 (get-in (read!) [:bounds :width])) "Close flushes the stable normal bounds")
      (is (:saved? @state))
      (studio/save-window-preferences! (assoc normal :width 1300) file state 2000000000 false)
      (studio/save-window-preferences! (assoc normal :normal 0 :width 1728) file state 2000000001 true)
      (is (= 1300 (get-in (read!) [:bounds :width])) "Closing maximized flushes the prior normal bounds")
      (studio/save-window-preferences! (assoc normal :routing-visible? false) file state 3000000000 true)
      (is (= {:routing-visible? false} (:panels (read!))))
      (studio/save-window-preferences! (assoc normal :routing-visible? true) file state 4000000000 false)
      (is (false? (get-in (read!) [:panels :routing-visible?])) "Panel-only edits use the same debounce")
      (studio/save-window-preferences! (assoc normal :routing-visible? true) file state 4500000000 false)
      (is (true? (get-in (read!) [:panels :routing-visible?])))
      (studio/save-window-preferences! (assoc normal :routing-visible? true :editor-top 380.0) file state 5000000000 true)
      (is (= {:routing-visible? true :editor-top 380.0} (:panels (read!))))
      (let [failed (atom nil) impossible (java.io.File. file "child.edn")]
        (is (thrown? Exception (studio/save-window-preferences! normal impossible failed 0 true)))
        (is (string? (:error @failed)))
        (is (nil? (studio/save-window-preferences! normal impossible failed 1000000000 false))
            "Don't retry a failed disk write on every display refresh")
        (is (thrown? Exception (studio/save-window-preferences! normal impossible failed 1000000001 true))
            "Explicit flush may retry"))
      (finally (java.nio.file.Files/deleteIfExists path)))))

(deftest routing-visibility-api-schema
  ;; Validate only: don't replace the live worker or enqueue audio commands.
  (doseq [visible [true false]]
    (let [command {:op :view/routing :args {:visible visible}}]
      (is (= command (#'studio/validate-command! command)))))
  (doseq [args [{} {:visible 1} {:visible "true"} {:visible nil} {:visible false :mute true}]]
    (is (thrown? clojure.lang.ExceptionInfo
          (#'studio/validate-command! {:op :view/routing :args args})))))

(deftest workspace-mode-api-schema
  (is (= #{:edit :record} (set (keys (:workspace-modes (studio/capabilities))))))
  (is (= {:mode :workspace-mode} (get-in (studio/capabilities) [:commands :view/mode])))
  (doseq [mode [:edit :record]]
    (let [command {:op :view/mode :args {:mode mode}}]
      (is (= command (#'studio/validate-command! command)))))
  (doseq [args [{} {:mode nil} {:mode :take-grid} {:mode "record"}
                {:mode :record :record true}]]
    (is (thrown? clojure.lang.ExceptionInfo
          (#'studio/validate-command! {:op :view/mode :args args})))))

(deftest editor-divider-api-schema
  (let [command {:op :view/editor :args {:top 520.0}}]
    (is (= command (#'studio/validate-command! command))))
  (doseq [top [-1 Double/NaN Double/POSITIVE_INFINITY "520" nil]]
    (is (thrown? clojure.lang.ExceptionInfo
          (#'studio/validate-command! {:op :view/editor :args {:top top}})))))

(deftest focus-marshals-to-render-thread
  (let [queued (promise) completed (promise) calls (atom [])]
    (with-redefs [core/on-render! (fn [f] (deliver queued f) completed)
                  studio/focus-window-native! #(swap! calls conj (Thread/currentThread))]
      (let [request (future (studio/focus-window!))
            callback (deref queued 2000 nil)]
        (try
          (is (fn? callback))
          (is (empty? @calls) "Enqueuing must not touch native window state")
          (when callback (callback))
          (is (= [(Thread/currentThread)] @calls))
          (finally (deliver completed {:value :focused})))
        (is (= :focused (deref request 2000 :timeout)))))))

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
                        {:op :view/routing :args {:visible false}}
                        {:op :alert/dismiss}
                        {:op :record/fx} {:op :record/dry} {:op :record/toggle}]]
         (is (= :queued (:status (studio/submit! command)))))
       (doseq [command [{:op :record/enable :args {:enabled 1}}
                        {:op :playback/boost :args {:enabled "yes"}}
                        {:op :view/routing :args {:visible "yes"}}
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
