(ns aguafria.zig.inspection-transform-test
  (:require [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

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
