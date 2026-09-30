(ns aguafria.zig.precompile-benchmark
  "Measure first-call preparation benefits separately from JVM startup."
  (:refer-clojure :exclude [run!])
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]
            [aguafria.zig.precompile :as precompile]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.shell :as shell]))

(defn- body! []
  (let [array (k/var (az/array [1 2] :i32))
        start (k/var 0 :usize)]
    (k/= :_ (k/& start))
    (let [slice (az/slice array start 2)]
      (k/+= (az/get slice 1) 1)
      (let [result {:sum (az/value (k/+ 31 32))
                    :array (az/value array)
                    :element (az/value (az/get array 1))}]
        (assert (= {:sum 63 :array [1 3] :element 3} result))
        result))))

(defn- measure! [f]
  (let [builds (atom 0)
        sh shell/sh
        start (System/nanoTime)
        result (with-redefs [shell/sh (fn [& args]
                                        (when (= "build-lib" (second args))
                                          (swap! builds inc))
                                        (apply sh args))]
                 (f))]
    {:elapsed-ms (/ (- (System/nanoTime) start) 1e6)
     :builds @builds
     :result result}))

(defn run! [cache prepare?]
  (az/configure! {:cache-dir cache})
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-fixture))
  (if prepare?
    (let [fail! (fn [& _] (throw (ex-info "Native invocation during preparation" {})))]
      (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
        (measure! #(select-keys
                    (precompile/precompile!
                     {:analyze ['aguafria.zig.discovery-fixture]
                      :report-file (str cache "/report.edn")})
                    [:coverage]))))
    {:first-evaluation (measure! body!)
     :loaded-evaluation (measure! body!)}))
