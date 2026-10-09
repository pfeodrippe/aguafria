(ns aguafria.zig.namespace-image-parallel-test
  (:require [aguafria.zig :as a]
            [aguafria.zig.bundle :as bundle]
            [aguafria.zig.discovery :as discovery]
            [aguafria.zig.precompile :as precompile]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]
           [java.util.concurrent Callable CompletableFuture CountDownLatch Executors TimeUnit]))

(deftest single-bundle-keeps-a-rejected-startup-snapshot-after-a-later-fix
  (let [namespace (create-ns (gensym "aguafria.fused-image-fixture-"))
        original (runtime/configuration)
        directory (str (Files/createTempDirectory
                        "aguafria-fused-image-error-"
                        (make-array java.nio.file.attribute.FileAttribute 0)))
        collected (atom {})
        commands (atom [])
        run-command @#'runtime/run-command]
    (try
      (runtime/configure! {:cache-dir directory})
      (binding [*ns* namespace
                runtime/*compile-only?* true
                runtime/*source-only-registration?* true
                runtime/*prepared-namespace-images* (atom {})
                bundle/*preparing* collected
                bundle/*batch-validation?* true]
        (refer 'clojure.core)
        (require '[aguafria.zig :as a])
        (eval '(a/defconst number :u8 256))
        (eval '(a/defn read-number :u8 [] number))
        (let [before (runtime/precompile-namespace-load! (ns-name namespace))]
          (eval '(a/defconst number :u8 1))
          (let [after (runtime/precompile-namespace-load! (ns-name namespace))
                failure
                (with-redefs-fn
                  {#'runtime/run-command
                   (fn [command directory]
                     (when (= "build-lib" (second command))
                       (swap! commands conj command))
                     (run-command command directory))}
                  #(try (runtime/finish-precompile-bundles!
                         collected {:validate-pending? true})
                        nil
                        (catch clojure.lang.ExceptionInfo error error)))]
            (is (= [:pending-validation :pending-validation]
                   (mapv :status [before after])))
            (is (not= (get-in before [:artifact :hash])
                      (get-in after [:artifact :hash])))
            (is (= 1 (count @commands)))
            (is (= :bundle-compile (:aguafria/phase (ex-data failure))))
            (is (str/includes? (:err (ex-data failure))
                               "cannot represent integer value '256'"))
            (is (not (.exists (io/file directory "bundles" "index")))))))
      (finally
        (runtime/configure! original)
        (remove-ns (ns-name namespace))))))

(deftest delayed-image-builds-keep-old-source-errors-and-compiler-profile
  (let [namespace (create-ns (gensym "aguafria.frozen-image-fixture-"))
        original (runtime/configuration)
        images (atom {})
        release (CountDownLatch. 1)
        frozen (atom [])
        capture #'runtime/freeze-compilation-slice!
        original-freeze @capture
        fail! (fn [& _] (throw (ex-info "Preparation invoked native code" {})))]
    (with-open [executor (Executors/newFixedThreadPool 2 (.factory (Thread/ofVirtual)))]
      (try
        (let [[before after]
              (binding [*ns* namespace
                        runtime/*compile-only?* true
                        runtime/*source-only-registration?* true
                        runtime/*prepared-namespace-images* images
                        runtime/*precompile-build-submitter*
                        (fn [build]
                          (let [configuration (runtime/configuration)]
                            (.submit executor ^Callable
                                     (bound-fn []
                                       (assert (.await release 30 TimeUnit/SECONDS))
                                       (runtime/call-with-precompile-configuration
                                        configuration build)))))]
                (with-redefs-fn
                  {capture (fn [& args]
                             (let [plan (apply original-freeze args)]
                               (swap! frozen conj plan)
                               plan))
                   #'runtime/invoke! fail! #'runtime/invoke-with-result! fail!}
                  (fn []
                    (refer 'clojure.core)
                    (require '[aguafria.zig :as a])
                    (eval '(a/defconst number :u8 256))
                    (eval '(a/defn read-number :u8 [] number))
                    (let [before (runtime/precompile-namespace-load! (ns-name namespace))]
                      (eval '(a/defconst number :u8 1))
                      [before (runtime/precompile-namespace-load! (ns-name namespace))]))))]
          (is (= [:pending :pending] (mapv :status [before after])))
          (is (every? #(= (:optimize original)
                          (get-in % [:source-plan :compiler-options :optimize])) @frozen))
          (runtime/configure! {:optimize "fast"})
          (.countDown release)
          (let [bad (#'precompile/resolve-namespace-image! before)
                good (#'precompile/resolve-namespace-image! after)
                plan (second @frozen)]
            (is (= :failed (:status bad)))
            (is (str/includes? (str (:stderr bad) (:causes bad))
                               "cannot represent integer value '256'"))
            (is (= :prepared (:status good)))
            (is (not (:namespace-image-job bad)))
            (is (not (:namespace-image-job good)))
            (is (= (get-in plan [:source-plan :artifact :hash])
                   (get-in good [:artifact :hash])))
            (is (= 1 (:value (some #(when (= 'number (:name %)) %)
                                   (get-in @images [(str (ns-name namespace))
                                                    :snapshot :declarations])))))
            (is (= (get-in plan [:snapshot :declarations])
                   (get-in @images [(str (ns-name namespace)) :snapshot :declarations])))))
        (finally
          (.countDown release)
          (runtime/configure! original)
          (remove-ns (ns-name namespace)))))))

(deftest image-resolution-preserves-test-records-and-publishes-completed-status
  (let [image {:namespace 'fixture.image :status :pending :dependencies ["provider"]
               :test-checks [{:test 'fixture.image/example :status :prepared}]
               :test-owners [{:test 'fixture.image/example :status :prepared}]
               :namespace-image-job
               (CompletableFuture/completedFuture
                {:namespace 'fixture.image :status :prepared :artifact {:hash "frozen"}})}
        images (atom {"fixture.image" image})]
    (binding [runtime/*prepared-namespace-images* (atom {})]
      (#'precompile/resolve-precompile-images! images))
    (let [resolved (get @images "fixture.image")]
      (is (= :prepared (:status resolved)))
      (is (= (:test-checks image) (:test-checks resolved)))
      (is (= (:test-owners image) (:test-owners resolved)))
      (is (= (:dependencies image) (:dependencies resolved)))
      (is (= "frozen" (get-in resolved [:artifact :hash])))
      (is (not (:namespace-image-job resolved))))))

(deftest image-build-does-not-replan-against-live-registry
  (let [snapshot {:declarations [{:name 'old}] :materialization-type-declarations {}}
        source-plan {:artifact {:hash "old-inputs"}}
        slice {:compile-source "old source" :declarations [{:name 'old}]}
        expected {:hash "old-inputs" :library-path "old.dylib"}
        observed (atom nil)]
    (with-redefs-fn
      {#'runtime/compile-source-plan (fn [& _] (throw (ex-info "Replanned live inputs" {})))
       #'runtime/build-compile-source-plan! (fn [plan] (reset! observed plan) expected)}
      #(let [result (#'runtime/build-frozen-compilation-slice!
                     {:slice slice :snapshot snapshot :source-plan source-plan})]
         (is (= source-plan @observed))
         (is (= snapshot (get-in result [:compiled :compilation-snapshot])))
         (is (= "old-inputs" (get-in result [:compiled :hash])))
         (is (= (:declarations slice) (:declarations result)))))))

(deftest queued-images-require-immutable-compiler-inputs
  (doseq [[options expected]
          [[{:cache-safe? true :zig-args ["-ferror-tracing" "-funwind-tables"]} true]
           [{:cache-safe? true :zig-args ["external.o"]} false]
           [{:cache-safe? true :module-zig-args {"external" ["-Iinclude"]}} false]
           [{:cache-safe? false} false]]]
    (is (= expected (#'runtime/immutable-compiler-inputs? options)))))

(deftest explicit-callables-wait-for-their-namespace-image
  (let [directory (str (Files/createTempDirectory
                        "aguafria-image-order-"
                        (make-array java.nio.file.attribute.FileAttribute 0)))
        events (atom [])]
    (with-redefs-fn
      {#'precompile/load-namespace!
       (fn [_ images]
         (swap! images assoc "fixture.image"
                {:namespace 'fixture.image :status :pending
                 :namespace-image-job
                 (reify java.util.concurrent.Future
                   (get [_] (swap! events conj :image) {:status :prepared}))})
         (runtime/configuration))
       #'runtime/precompile-functions!
       (fn [_] (swap! events conj :callable) [])
       #'precompile/analyze-namespaces! (fn [& _] [])
       #'precompile/prepare-scalar-profiles! (fn [_] {:constructors []})}
      #(let [report (precompile/precompile!
                     {:namespaces ['fixture.image]
                      :report-file (str (io/file directory "report.edn"))})]
         (is (= [:image :callable] @events))
         (is (= :prepared (get-in report [:namespace-images 0 :status])))
         (is (not (get-in report [:namespace-images 0 :namespace-image-job])))))))
