(ns aguafria.zig.inspection-transform-test
  (:require [aguafria.zig.precompile :as precompile]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(deftest final-test-preparation-reports-failures-and-continues
  (let [calls (atom [])]
    (with-redefs [runtime/registered-declarations
                  (fn [_] [{:kind :fn :name 'helper}
                           {:kind :test :name 'valid}
                           {:kind :test :name 'invalid}])
                  runtime/precompile-test!
                  (fn [test]
                    (swap! calls conj test)
                    (if (= 'fixture.owner/invalid test)
                      (throw (ex-info "Rejected native test" {:aguafria/phase :zig-test}))
                      {:test test :status :prepared}))]
      (let [checks (#'precompile/prepare-native-test-owners! "fixture.owner")]
        (is (= '[fixture.owner/invalid fixture.owner/valid] @calls))
        (is (= [:failed :prepared] (mapv :status checks)))
        (is (= (first @calls) (:test (first checks))))
        (is (= "Rejected native test" (:message (first checks))))))))

(deftest final-native-test-preparation-shares-the-demand-library-plan
  (let [calls (atom [])
        artifact {:source-path "final.zig" :library-path "final.dylib" :command [:native-test]}]
    (with-redefs-fn
      {#'runtime/native-test-library!
       (fn [& args] (swap! calls conj args) artifact)}
      #(do
         (is (= {:test 'fixture.owner/example :status :prepared
                 :artifact (select-keys artifact [:library-path :source-path])}
                (runtime/precompile-test! 'fixture.owner/example)))
         (is (= [["fixture.owner" 'example]] @calls))
         (is (thrown? clojure.lang.ExceptionInfo (runtime/precompile-test! 'example)))
         (is (= 1 (count @calls)))))))

(deftest native-test-snapshots-exclude-jvm-adapters-from-roots-and-dependencies
  (let [module "fixture.native-test"
        helper {:module module :kind :fn :name 'helper :return :u32 :args [] :body [42]}
        selected {:module module :kind :test :name 'example :test-name "example" :body []}
        sibling (assoc selected :name 'sibling :test-name "sibling")
        adapter (assoc helper :name 'adapter :jvm-adapter? true)
        dependency-helper (assoc helper :module "fixture.provider" :name 'provider)
        dependency-adapter (assoc adapter :module "fixture.provider")
        roots (atom nil)
        dependency-declarations (atom nil)
        emitted (atom nil)]
    (with-redefs-fn
      {#'runtime/registry (atom {module {:definitions {[:fn 'helper] helper
                                                       [:test 'example] selected
                                                       [:test 'sibling] sibling
                                                       [:fn 'adapter] adapter}}})
       #'runtime/static-dependency-snapshot
       (fn [declarations transform]
         (reset! roots declarations)
         (reset! dependency-declarations (transform [dependency-helper dependency-adapter sibling]))
         {})
       #'runtime/compiler-options-for-declarations (fn [_ _] {})
       #'aguafria.zig.emitter/emit-module
       (fn [_ declarations] (reset! emitted declarations) "original native test")}
      #(let [snapshot (#'runtime/native-test-snapshot module 'example)]
         (is (= "original native test" (:source snapshot)))))
    (is (= ['helper 'example] (mapv :name @roots)))
    (is (= ['provider] (mapv :name @dependency-declarations)))
    (is (= @roots @emitted))
    (is (= selected (last @roots)))
    (is (false? (:export? (first @roots))))))

(deftest transformed-inspection-plans-the-exact-emitted-graph
  (let [cache (str (Files/createTempDirectory
                    "aguafria-inspection-transform-"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
        module "fixture.inspection-transform"
        original {:name 'original :dependency "old-provider"}
        adapter {:name 'adapter :jvm-adapter? true :dependency "adapter-provider"}
        graphs (atom [])
        commands (atom [])
        original-options {:cache-dir cache :zig "zig" :deps ["old-provider"]}]
    (with-redefs-fn
      {#'runtime/inspection-context
       (fn [_] {:module module :declarations [original] :options original-options})
       #'runtime/ensure-converted-dependency-sources! (fn [& _])
       #'runtime/static-dependency-snapshot
       (fn [declarations _]
         (swap! graphs conj (vec declarations))
         (zipmap (map :dependency declarations) (repeat {})))
       #'runtime/compiler-options-for-declarations
       (fn [options _]
         (assoc original-options :deps (vec (keys (:dependency-snapshot options)))))
       #'runtime/root-module-arguments
       (fn [_ options] (:deps options))
       #'aguafria.zig.project/materialize-module-assets! (fn [& _])
       #'shell/sh (fn [& args] (swap! commands conj args) {:exit 0 :out "" :err ""})}
      (fn []
        (runtime/inspect-module! module (fn [_] {:source "" :declarations [adapter]}))
        (runtime/inspect-module! module (fn [_] {:source ""}))))
    (is (= [[adapter]] @graphs))
    (is (some #{"adapter-provider"} (first @commands)))
    (is (not-any? #{"old-provider"} (first @commands)))
    (is (some #{"old-provider"} (second @commands)))
    (is (not-any? #{"adapter-provider"} (second @commands)))))

(deftest transformed-inspection-compiles-an-adapters-new-native-dependency
  (let [cache (str (Files/createTempDirectory
                    "aguafria-inspection-transform-native-"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
        body
        '(let [prefix (str "aguafria.inspection-transform-" (random-uuid))
               old (create-ns (symbol (str prefix ".old")))
               added (create-ns (symbol (str prefix ".added")))
               root (create-ns (symbol (str prefix ".root")))
               root-module (str (ns-name root))
               added-module (str (ns-name added))
               old-module (str (ns-name old))]
           (binding [aguafria.zig.runtime/*source-only-registration?* true]
             (doseq [context [old added root]]
               (binding [*ns* context]
                 (refer 'clojure.core)
                 (alias 'a 'aguafria.zig)))
             (binding [*ns* old] (eval '(a/defconst amount :u32 41)))
             (binding [*ns* added] (eval '(a/defstruct Item [[:value :u32]])))
             (binding [*ns* root]
               (alias 'old (ns-name old))
               (eval '(a/defn original :u32 [] old/amount))))
           (let [adapter {:module root-module :kind :fn :name 'adapter
                          :qualified-name (symbol root-module "adapter")
                          :declaration-key [:fn 'adapter] :jvm-adapter? true
                          :return (aguafria.zig.emitter/qualify-type root (symbol added-module "Item")) :args []
                          :body [{:value 42}]}
                 result (aguafria.zig.runtime/inspect-module!
                         root-module
                         (fn [_]
                           {:declarations [adapter]
                            :source (str (aguafria.zig.emitter/emit-module root-module [adapter])
                                         "\ncomptime { const T = @typeInfo(@TypeOf(adapter)).@\"fn\".return_type.?; if (@sizeOf(T) != 4) @compileError(\"wrong adapter return layout\"); }\n")}))]
             {:exit (:exit result) :err (:err result)
              :new-dependency? (boolean (some #{added-module} (:command result)))
              :old-dependency? (boolean (some #{old-module} (:command result)))}))
        code `(do
                (require 'aguafria.zig 'aguafria.zig.runtime 'aguafria.zig.emitter)
                (aguafria.zig.runtime/configure! {:cache-dir ~cache})
                (with-redefs [aguafria.zig.runtime/invoke!
                              (fn [& _#] (throw (ex-info "Inspection invoked native code" {})))
                              aguafria.zig.runtime/invoke-with-result!
                              (fn [& _#] (throw (ex-info "Inspection invoked native code" {})))]
                  (prn (eval '~body)))
                (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED" "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (spit (io/file cache "result.edn") (pr-str result))
    (is (zero? (:exit result)) (:err result))
    (when (zero? (:exit result))
      (let [inspection (edn/read-string (:out result))]
        (is (= {:exit 0 :err "" :new-dependency? true :old-dependency? false}
               inspection))))))
