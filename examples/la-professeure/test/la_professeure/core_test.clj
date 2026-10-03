(ns la-professeure.core-test
  (:require [aguafria.zig.value :as value]
            [aguafria.zig.runtime :as runtime]
            [clojure.test :refer [deftest is]]
            [la-professeure.build :as build]
            [la-professeure.core :as core]))

(defn- native-boolean [boolean]
  (value/native-value {:type :bool}
                      #(hash-map :representation :scalar :value boolean)))

(deftest stage-respects-native-boolean-results
  (doseq [initialized? [false true]]
    (let [calls (atom [])
          resolve-original ns-resolve
          native-functions
          {'initialize! #(do (swap! calls conj :initialize) (native-boolean initialized?))
           'tick! #(do
                     (when (some #{:tick} @calls)
                       (throw (AssertionError. "Closed stage was ticked again")))
                     (swap! calls conj :tick)
                     (native-boolean false))
           'snapshot #(throw (AssertionError. "Closed stage must not produce a snapshot"))
           'shutdown! #(swap! calls conj :shutdown)}]
      (with-redefs [core/status (atom {})
                    ns-resolve (fn [namespace symbol]
                                 (if (= namespace 'la-professeure.scene)
                                   (get native-functions symbol)
                                   (resolve-original namespace symbol)))]
        (if initialized?
          (#'core/run-stage!)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Stage failed to initialize"
                               (#'core/run-stage!))))
        (is (= (if initialized? [:initialize :tick :shutdown] [:initialize :shutdown])
               @calls))
        (is (= :closed (:state @core/status)))))))

(deftest shader-watcher-respects-native-publication-result
  (doseq [[accepted? expected] [[true :reloaded] [false :error]]]
    (let [status (atom {})
          reads (atom 0)
          builds (atom 0)
          prepared (atom [])
          resolve-original ns-resolve]
      (with-redefs-fn
        {#'core/status status
         #'core/shader-reload-enabled? true
         #'core/shader-stamp #(if (= 1 (swap! reads inc)) 0 1)
         #'build/shaders! #(swap! builds inc)
         #'runtime/precompile-function! #(swap! prepared conj %)
         #'core/on-render! (fn [f]
                            (is (= ['la-professeure.gpu/reload-shaders!] @prepared))
                            (deliver (promise) {:value (f)}))
         #'clojure.core/ns-resolve
         (fn [namespace symbol]
           (if (= [namespace symbol] ['la-professeure.gpu 'reload-shaders!])
             #(native-boolean accepted?)
             (resolve-original namespace symbol)))}
        (fn []
          (let [watcher (core/start-shader-watcher!)]
            (try
              (loop [remaining 100]
                (when (and (pos? remaining) (nil? (:shaders @status)))
                  (Thread/sleep 20)
                  (recur (dec remaining))))
              (is (= expected (get-in @status [:shaders :state])))
              (is (= 1 @builds))
              (finally
                (future-cancel watcher)
                (try @watcher
                     (catch java.util.concurrent.CancellationException _))))))))))
